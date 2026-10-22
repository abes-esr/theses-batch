package fr.theses.batch.util.parser;

import lombok.extern.slf4j.Slf4j;
import org.apache.fontbox.cmap.CMap;
import org.apache.fontbox.cmap.CMapParser;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.DefaultResourceCache;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDSimpleFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.encoding.Encoding;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reconstitue la correspondance Unicode des polices PDF qui n'en déclarent pas (ou pas entièrement),
 * à l'aide de la même police installée sur le système.
 * <p>
 * Les PDF produits par Word identifient souvent chaque glyphe par son numéro (GID) dans la police
 * TrueType d'origine : nom de glyphe « g71 » dans les polices simples, CID égal au GID dans les polices
 * composites Identity-H. La table cmap de la police système permet alors de retrouver le caractère.
 * Chaque correspondance n'est retenue que si la largeur du glyphe déclarée dans le PDF est celle du
 * glyphe de la police système : une autre version de la police, ou un PDF qui ne suit pas cette
 * convention, ne produit donc pas de texte erroné.
 * <p>
 * La correspondance est écrite en mémoire comme table /ToUnicode de la police, en conservant les
 * entrées existantes ; le fichier PDF n'est pas modifié.
 */
@Slf4j
@Component
public class SystemFontUnicodeRecovery {

    private static final Pattern GID_GLYPH_NAME = Pattern.compile("^g(\\d+)$");
    private static final float WIDTH_TOLERANCE = 1.5f;
    private static final int BFCHAR_BLOCK_SIZE = 100;

    private final SystemFontIndex systemFontIndex;

    public SystemFontUnicodeRecovery(SystemFontIndex systemFontIndex) {
        this.systemFontIndex = systemFontIndex;
    }

    /**
     * Complète les polices des premières pages du document.
     * <p>
     * Les exceptions sont capturées car un PDF malformé peut contenir des pages
     * avec une structure invalide (ex: COSName au lieu de PDPage). Ces erreurs
     * sont considérées comme des problèmes de données, pas des bugs.
     *
     * @param document    Document ouvert
     * @param pagesToRead Nombre de pages qui seront lues
     * @return pour chaque police complétée (dictionnaire PDF), les codes dont le caractère a été reconstitué
     */
    public Map<COSDictionary, Set<Integer>> recover(PDDocument document, int pagesToRead) {
        Map<COSDictionary, Set<Integer>> recovered = new IdentityHashMap<>();
        Set<COSBase> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int i = 0; i < pagesToRead; i++) {
            try {
                scan(document.getPage(i).getResources(), visited, recovered);
            } catch (IllegalStateException e) {
                // PDF malformé : une page a une structure inattendue
                log.warn("Page {} ignorée (PDF malformé) : {}", i, e.getMessage());
                // On passe à la page suivante sans propager l'exception
            }
        }
        if (!recovered.isEmpty()) {
            // les polices déjà instanciées ont lu l'ancienne table /ToUnicode : on repart d'un cache vide
            document.setResourceCache(new DefaultResourceCache());
        }
        return recovered;
    }

    private void scan(PDResources resources, Set<COSBase> visited, Map<COSDictionary, Set<Integer>> recovered) {
        if (resources == null || !visited.add(resources.getCOSObject())) {
            return;
        }
        COSDictionary fonts = resources.getCOSObject().getCOSDictionary(COSName.FONT);
        for (COSName name : resources.getFontNames()) {
            try {
                // avant toute instanciation de la police, ici comme dans PDFTextStripper
                COSDictionary fontDictionary = fonts == null ? null : fonts.getCOSDictionary(name);
                if (CidToGidMapLimiter.limit(fontDictionary)) {
                    log.warn("Police {} : table /CIDToGIDMap démesurée, tronquée à {} octets",
                            fontDictionary.getNameAsString(COSName.BASE_FONT), CidToGidMapLimiter.MAX_DECODED_BYTES);
                }
                PDFont font = resources.getFont(name);
                if (font != null && visited.add(font.getCOSObject())) {
                    Set<Integer> codes = recover(font);
                    if (!codes.isEmpty()) {
                        recovered.put(font.getCOSObject(), codes);
                    }
                }
            } catch (IOException |IllegalStateException e) {
                log.debug("Police {} ignorée : {}", name.getName(), e.getMessage());
            }
        }
        // Formulaires lus au niveau COS, sans resources.getXObject() qui les mettrait en cache : après
        // chaque page, PDPage.removeResources retire du cache chaque formulaire puis descend dans ses
        // ressources, un niveau de récursion par formulaire en cache. Quand les formulaires partagent les
        // ressources de la page (5 859 dans un PDF de prod), la pile déborde.
        COSDictionary xObjects = resources.getCOSObject().getCOSDictionary(COSName.XOBJECT);
        for (COSName name : resources.getXObjectNames()) {
            if (xObjects.getDictionaryObject(name) instanceof COSStream stream
                    && COSName.FORM.equals(stream.getCOSName(COSName.SUBTYPE))
                    && stream.getCOSDictionary(COSName.RESOURCES) instanceof COSDictionary formResources) {
                scan(new PDResources(formResources, resources.getResourceCache()), visited, recovered);
            }
        }
    }

    /**
     * @return les codes de cette police dont le caractère a été reconstitué (vide si rien n'a changé)
     */
    private Set<Integer> recover(PDFont font) throws IOException {
        SystemFontIndex.SystemFont systemFont = systemFontIndex.find(font.getName()).orElse(null);
        if (systemFont == null) {
            return Set.of();
        }
        COSBase toUnicode = font.getCOSObject().getDictionaryObject(COSName.TO_UNICODE);
        if (toUnicode != null && !(toUnicode instanceof COSStream)) {
            return Set.of();
        }
        CMap existing = toUnicode == null ? null : parseCMap((COSStream) toUnicode);

        Map<Integer, String> mapping = new TreeMap<>();
        Set<Integer> recoveredCodes = new HashSet<>();
        int codeLength;
        if (font instanceof PDType0Font type0) {
            String encoding = type0.getCMap() == null ? null : type0.getCMap().getName();
            if (encoding == null || !encoding.startsWith("Identity-")) {
                return Set.of();
            }
            codeLength = 2;
            int maxCode = Math.min(systemFont.numberOfGlyphs(), 0x10000);
            for (int code = 0; code < maxCode; code++) {
                map(font, code, type0.codeToCID(code), codeLength, existing, systemFont, mapping, recoveredCodes);
            }
        } else if (font instanceof PDSimpleFont simple && simple.getEncoding() != null) {
            codeLength = 1;
            Encoding encoding = simple.getEncoding();
            for (int code = 0; code < 256; code++) {
                Matcher gidName = GID_GLYPH_NAME.matcher(encoding.getName(code));
                int gid = gidName.matches() ? Integer.parseInt(gidName.group(1)) : -1;
                map(font, code, gid, codeLength, existing, systemFont, mapping, recoveredCodes);
            }
        } else {
            return Set.of();
        }

        if (recoveredCodes.isEmpty()) {
            return Set.of();
        }
        writeToUnicode(font, mapping, codeLength);
        log.debug("Police {} : {} caractère(s) reconstitué(s) via {}", font.getName(), recoveredCodes.size(), systemFont.name());
        return recoveredCodes;
    }

    private void map(PDFont font, int code, int gid, int codeLength, CMap existing, SystemFontIndex.SystemFont systemFont,
                     Map<Integer, String> mapping, Set<Integer> recoveredCodes) throws IOException {
        String unicode = existing == null ? null : existing.toUnicode(code, codeLength);
        if (unicode != null) {
            mapping.put(code, unicode);
            return;
        }
        if (gid <= 0 || gid >= systemFont.numberOfGlyphs()
                || Math.abs(font.getWidth(code) - systemFont.widthOf(gid)) > WIDTH_TOLERANCE) {
            return;
        }
        unicode = systemFont.unicodeOf(gid);
        if (unicode != null) {
            mapping.put(code, unicode);
            recoveredCodes.add(code);
        }
    }

    private static CMap parseCMap(COSStream stream) {
        try (InputStream input = stream.createInputStream()) {
            return new CMapParser().parse(new RandomAccessReadBuffer(input));
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeToUnicode(PDFont font, Map<Integer, String> mapping, int codeLength) throws IOException {
        String codeFormat = codeLength == 1 ? "%02X" : "%04X";
        StringBuilder cmap = new StringBuilder("""
                /CIDInit /ProcSet findresource begin
                12 dict begin
                begincmap
                /CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def
                /CMapName /Adobe-Identity-UCS def
                /CMapType 2 def
                1 begincodespacerange
                """);
        cmap.append('<').append(String.format(codeFormat, 0)).append("> <")
                .append(String.format(codeFormat, codeLength == 1 ? 0xFF : 0xFFFF)).append(">\nendcodespacerange\n");

        List<Map.Entry<Integer, String>> entries = new ArrayList<>(mapping.entrySet());
        for (int start = 0; start < entries.size(); start += BFCHAR_BLOCK_SIZE) {
            List<Map.Entry<Integer, String>> block = entries.subList(start, Math.min(start + BFCHAR_BLOCK_SIZE, entries.size()));
            cmap.append(block.size()).append(" beginbfchar\n");
            for (Map.Entry<Integer, String> entry : block) {
                cmap.append('<').append(String.format(codeFormat, entry.getKey())).append("> <");
                for (char c : entry.getValue().toCharArray()) {
                    cmap.append(String.format("%04X", (int) c));
                }
                cmap.append(">\n");
            }
            cmap.append("endbfchar\n");
        }
        cmap.append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n");

        COSStream stream = new COSStream();
        try (OutputStream output = stream.createOutputStream()) {
            output.write(cmap.toString().getBytes(StandardCharsets.US_ASCII));
        }
        font.getCOSObject().setItem(COSName.TO_UNICODE, stream);
    }
}

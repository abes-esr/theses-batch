package fr.theses.batch.support;

import org.apache.fontbox.ttf.CmapLookup;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDFormContentStream;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.encoding.DictionaryEncoding;
import org.apache.pdfbox.pdmodel.font.encoding.GlyphList;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Génère des PDF de test.
 * <p>
 * {@link #create} écrit en Helvetica (police standard, sans fichier à embarquer). Les autres méthodes
 * embarquent LiberationSans, livrée dans le jar PDFBox, pour reproduire les PDF Word dont les polices
 * n'ont pas de correspondance Unicode : police composite Identity-H sans /ToUnicode, ou police simple
 * dont les glyphes sont nommés « gN », N étant le numéro du glyphe dans la police TrueType.
 */
public final class TestPdfs {

    /**
     * Motif ANR de référence, identique à celui de la configuration locale
     */
    public static final String ANR_PATTERN =
            "\\b(?:ANR-(?:\\d{2}-)?[A-Za-z0-9]{4,8}(?:-\\d{1,4})?|Agence nationale de la recherche|Labex|PIA)\\b";

    /**
     * Nom PostScript de la police embarquée, tel qu'il apparaît dans le PDF
     */
    public static final String FONT_NAME = "LiberationSans";

    private static final String FONT_RESOURCE = "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf";

    private TestPdfs() {
    }

    /**
     * Crée un PDF dont chaque page porte une ligne de texte ; une chaîne vide donne une page blanche
     *
     * @param file  fichier à créer (répertoires parents créés au besoin)
     * @param pages texte de chaque page
     * @return le fichier créé
     */
    public static Path create(Path file, String... pages) throws IOException {
        Files.createDirectories(file.getParent());
        try (PDDocument document = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (String text : pages) {
                PDPage page = new PDPage();
                document.addPage(page);
                if (text.isEmpty()) {
                    continue;
                }
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    showText(content, font, text);
                }
            }
            document.save(file.toFile());
        }
        return file;
    }

    /**
     * Crée un PDF d'une page en police composite Identity-H (CID = numéro de glyphe), comme Word.
     * Comme dans les sous-ensembles embarqués par Word, la police embarquée n'a pas de table cmap :
     * sans elle, PDFBox retrouverait le texte d'une police sans /ToUnicode par cette table (PDFBOX-5324).
     *
     * @param inForm true pour placer le texte dans un Form XObject plutôt que directement sur la page
     * @param tweak  modification du dictionnaire de la police avant enregistrement (ex. retrait de /ToUnicode)
     */
    public static Path createType0(Path file, String text, boolean inForm, Consumer<COSDictionary> tweak) throws IOException {
        Files.createDirectories(file.getParent());
        try (PDDocument document = new PDDocument(); InputStream ttf = fontStream()) {
            PDType0Font font = PDType0Font.load(document, ttf, false);
            write(document, font, text, inForm);

            byte[] withoutCmap = fontBytesWithoutCmap();
            PDStream fontFile = new PDStream(document, new ByteArrayInputStream(withoutCmap), COSName.FLATE_DECODE);
            fontFile.getCOSObject().setInt(COSName.LENGTH1, withoutCmap.length);
            font.getDescendantFont().getFontDescriptor().setFontFile2(fontFile);

            tweak.accept(font.getCOSObject());
            document.save(file.toFile());
        }
        return file;
    }

    /**
     * Police composite Identity-H sans /ToUnicode : aucun caractère n'est extractible sans reconstitution
     */
    public static Path createType0WithoutToUnicode(Path file, String text) throws IOException {
        return createType0(file, text, false, font -> font.removeItem(COSName.TO_UNICODE));
    }

    /**
     * Crée un PDF d'une page en police TrueType simple dont l'encodage nomme chaque glyphe « gN ».
     * Comme Word, les codes sont attribués à la suite à partir de 1 : un code ne dit rien du caractère,
     * et le repli de PDFTextStripper (code pris pour le caractère) ne donne que des caractères de contrôle.
     *
     * @param wrongWidthChar caractère dont la largeur déclarée dans le PDF est faussée (0 pour aucun)
     */
    public static Path createGidNamed(Path file, String text, char wrongWidthChar) throws IOException {
        Files.createDirectories(file.getParent());
        List<Integer> chars = text.chars().distinct().sorted().boxed().toList();
        try (PDDocument document = new PDDocument(); InputStream ttf = fontStream()) {
            // Encodage d'écriture : codes 1..n vers les noms standard, pour que PDFBox sache encoder le texte
            COSArray standardNames = new COSArray();
            standardNames.add(COSInteger.ONE);
            chars.forEach(c -> standardNames.add(COSName.getPDFName(GlyphList.getAdobeGlyphList().codePointToName(c))));
            PDTrueTypeFont font = PDTrueTypeFont.load(document, ttf,
                    new DictionaryEncoding(COSName.WIN_ANSI_ENCODING, standardNames));
            write(document, font, text, false);

            // Encodage enregistré : mêmes codes, glyphes nommés d'après leur numéro
            COSArray gidNames = new COSArray();
            gidNames.add(COSInteger.ONE);
            chars.forEach(c -> gidNames.add(COSName.getPDFName("g" + glyphId(c))));
            COSDictionary encoding = new COSDictionary();
            encoding.setItem(COSName.TYPE, COSName.ENCODING);
            encoding.setItem(COSName.DIFFERENCES, gidNames);
            COSDictionary fontDict = font.getCOSObject();
            fontDict.setItem(COSName.ENCODING, encoding);
            fontDict.removeItem(COSName.TO_UNICODE);

            if (wrongWidthChar != 0) {
                COSArray widths = fontDict.getCOSArray(COSName.WIDTHS);
                int code = chars.indexOf((int) wrongWidthChar) + 1;
                int index = code - fontDict.getInt(COSName.FIRST_CHAR);
                float width = ((COSNumber) widths.getObject(index)).floatValue();
                widths.set(index, new COSFloat(width + 100));
            }
            document.save(file.toFile());
        }
        return file;
    }

    /**
     * @return LiberationSans dont la table « cmap » est renommée, donc introuvable : seuls les numéros de glyphe restent
     */
    private static byte[] fontBytesWithoutCmap() throws IOException {
        byte[] font;
        try (InputStream ttf = fontStream()) {
            font = ttf.readAllBytes();
        }
        ByteBuffer buffer = ByteBuffer.wrap(font);
        int numTables = buffer.getShort(4) & 0xFFFF;
        for (int i = 0; i < numTables; i++) {
            int tagPosition = 12 + 16 * i;
            if (buffer.getInt(tagPosition) == 0x636D6170) { // « cmap »
                buffer.put(tagPosition, (byte) 'x');
            }
        }
        return font;
    }

    /**
     * @return le numéro de glyphe du caractère dans LiberationSans, égal au CID dans un PDF Identity-H
     */
    public static int glyphId(int codePoint) {
        try (InputStream ttf = fontStream()) {
            TrueTypeFont font = new TTFParser().parse(new RandomAccessReadBuffer(ttf));
            CmapLookup cmap = font.getUnicodeCmapLookup();
            return cmap.getGlyphId(codePoint);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Copie LiberationSans dans un répertoire de polices système
     *
     * @return le fichier .ttf créé
     */
    public static Path installFont(Path fontsDir) throws IOException {
        Files.createDirectories(fontsDir);
        Path target = fontsDir.resolve("LiberationSans-Regular.ttf");
        try (InputStream ttf = fontStream()) {
            Files.copy(ttf, target);
        }
        return target;
    }

    /**
     * Écrit LiberationSans sous forme de collection TrueType (.ttc) d'une seule police : en-tête « ttcf »
     * suivi de la police, dont les positions de tables sont décalées de la taille de l'en-tête
     *
     * @return le fichier .ttc créé
     */
    public static Path installFontCollection(Path fontsDir) throws IOException {
        byte[] font;
        try (InputStream ttf = fontStream()) {
            font = ttf.readAllBytes();
        }
        int headerSize = 16;
        ByteBuffer tables = ByteBuffer.wrap(font);
        int numTables = tables.getShort(4) & 0xFFFF;
        for (int i = 0; i < numTables; i++) {
            int offsetPosition = 12 + 16 * i + 8;
            tables.putInt(offsetPosition, tables.getInt(offsetPosition) + headerSize);
        }
        ByteBuffer collection = ByteBuffer.allocate(headerSize + font.length);
        collection.put("ttcf".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                .putInt(0x00010000)
                .putInt(1)
                .putInt(headerSize)
                .put(font);

        Files.createDirectories(fontsDir);
        return Files.write(fontsDir.resolve("LiberationSans.ttc"), collection.array());
    }

    private static InputStream fontStream() {
        InputStream stream = TestPdfs.class.getResourceAsStream(FONT_RESOURCE);
        if (stream == null) {
            throw new IllegalStateException("Police de test introuvable dans le jar PDFBox : " + FONT_RESOURCE);
        }
        return stream;
    }

    private static void write(PDDocument document, PDFont font, String text, boolean inForm) throws IOException {
        PDPage page = new PDPage();
        document.addPage(page);
        if (!inForm) {
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                showText(content, font, text);
            }
            return;
        }
        PDFormXObject form = new PDFormXObject(document);
        form.setResources(new PDResources());
        form.setBBox(PDRectangle.LETTER);
        // PDFormContentStream et PDPageContentStream n'ont pas de supertype public : pas de showText() commun
        try (PDFormContentStream content = new PDFormContentStream(form)) {
            content.beginText();
            content.setFont(font, 12);
            content.newLineAtOffset(50, 700);
            content.showText(text);
            content.endText();
        }
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            content.drawForm(form);
        }
    }

    private static void showText(PDPageContentStream content, PDFont font, String text) throws IOException {
        content.beginText();
        content.setFont(font, 12);
        content.newLineAtOffset(50, 700);
        content.showText(text);
        content.endText();
    }
}

package fr.theses.batch.util.parser;

import lombok.extern.slf4j.Slf4j;
import org.apache.fontbox.ttf.CmapLookup;
import org.apache.fontbox.ttf.FontHeaders;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.io.RandomAccessRead;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Index des polices TrueType installées dans app.anr.fonts-dir, retrouvées à partir du nom
 * d'une police PDF (« ABCDEF+TimesNewRoman,Bold », « Calibri-Bold-Identity-H », « ArialMT »...).
 * <p>
 * Le répertoire n'est parcouru qu'au premier besoin, et chaque police n'est chargée qu'une fois.
 * Sans app.anr.fonts-dir, aucune police n'est trouvée et la reconstitution est désactivée.
 * <p>
 * L'index est partagé par les threads de traitement : au chargement, largeur et caractère de chaque
 * glyphe sont recopiés dans des tableaux et le fichier de police est refermé. Une police chargée est
 * donc immuable, et {@link #find} ne prend aucun verrou global.
 */
@Slf4j
@Component
public class SystemFontIndex {

    private static final Pattern SUBSET_PREFIX = Pattern.compile("^[A-Z]{6}\\+");
    private static final Pattern IDENTITY_SUFFIX = Pattern.compile("-Identity-[HV]$");
    private static final Pattern POSTSCRIPT_SUFFIX = Pattern.compile("(PSMT|PS|MT)$");
    private static final Pattern PLAIN_STYLE = Pattern.compile("(?i)regular|normal|bold|italic|oblique|bold italic|bold oblique");

    @Value("${app.anr.fonts-dir:}")
    private String fontsDir;

    private volatile Map<String, FontLocation> locations;
    private final Map<String, Optional<SystemFont>> loaded = new ConcurrentHashMap<>();

    private record FontLocation(Path file, String postScriptName, boolean collection) {
    }

    /**
     * Police système installée, réduite à ce qu'il faut pour traduire un numéro de glyphe (GID).
     * Les tableaux sont remplis au chargement et ne sont plus modifiés ensuite.
     */
    public static final class SystemFont {

        private final String name;
        private final float[] widths;
        private final String[] unicode;

        /**
         * @param name    Nom PostScript de la police
         * @param widths  Largeur de chaque glyphe, en millièmes d'em comme les largeurs déclarées dans un PDF
         * @param unicode Caractère dessiné par chaque glyphe, null si la police ne l'associe à aucun
         */
        SystemFont(String name, float[] widths, String[] unicode) {
            this.name = name;
            this.widths = widths;
            this.unicode = unicode;
        }

        /**
         * @return le nom PostScript de la police
         */
        public String name() {
            return name;
        }

        /**
         * @return le caractère dessiné par ce glyphe, ou null si la police ne l'associe à aucun
         */
        public String unicodeOf(int gid) {
            return gid >= 0 && gid < unicode.length ? unicode[gid] : null;
        }

        /**
         * @return la largeur du glyphe en millièmes d'em, comme les largeurs déclarées dans un PDF
         */
        public float widthOf(int gid) {
            return widths[gid];
        }

        public int numberOfGlyphs() {
            return widths.length;
        }

        @Override
        public String toString() {
            return "SystemFont[" + name + ", " + widths.length + " glyphes]";
        }
    }

    /**
     * Cherche la police système correspondant à une police PDF (même famille, même graisse, même style).
     *
     * @param pdfFontName Nom de la police dans le PDF
     * @return la police installée, ou vide si aucune ne correspond
     */
    public Optional<SystemFont> find(String pdfFontName) {
        String key = keyOfPdfFont(pdfFontName);
        if (key == null) {
            return Optional.empty();
        }
        return loaded.computeIfAbsent(key, k -> Optional.ofNullable(locations().get(k)).map(this::load));
    }

    static String keyOfPdfFont(String pdfFontName) {
        if (pdfFontName == null || pdfFontName.isBlank()) {
            return null;
        }
        String name = IDENTITY_SUFFIX.matcher(SUBSET_PREFIX.matcher(pdfFontName).replaceFirst("")).replaceFirst("");
        int separator = indexOfStyleSeparator(name);
        String family = separator < 0 ? name : name.substring(0, separator);
        String style = separator < 0 ? "" : name.substring(separator + 1);
        family = POSTSCRIPT_SUFFIX.matcher(family).replaceFirst("");
        return key(family, style.contains("Bold"), style.contains("Italic") || style.contains("Oblique"));
    }

    private static int indexOfStyleSeparator(String name) {
        int comma = name.indexOf(',');
        return comma >= 0 ? comma : name.indexOf('-');
    }

    private static String key(String family, boolean bold, boolean italic) {
        String normalized = family.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized + (bold ? "|bold" : "") + (italic ? "|italic" : "");
    }

    private Map<String, FontLocation> locations() {
        Map<String, FontLocation> current = locations;
        if (current == null) {
            synchronized (this) {
                current = locations;
                if (current == null) {
                    current = scanFontsDir();
                    locations = current;
                }
            }
        }
        return current;
    }

    private Map<String, FontLocation> scanFontsDir() {
        Map<String, FontLocation> found = new HashMap<>();
        if (fontsDir == null || fontsDir.isBlank()) {
            log.info("app.anr.fonts-dir non renseigné : pas de reconstitution du texte via les polices système");
            return found;
        }
        Path dir = Path.of(fontsDir);
        if (!Files.isDirectory(dir)) {
            log.warn("Répertoire de polices introuvable (app.anr.fonts-dir) : {}", fontsDir);
            return found;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            files.filter(Files::isRegularFile).forEach(file -> register(file, found));
        } catch (IOException e) {
            log.warn("Impossible de parcourir le répertoire de polices {} : {}", fontsDir, e.getMessage());
        }
        log.info("{} police(s) système indexée(s) dans {}", found.size(), fontsDir);
        return found;
    }

    private static void register(Path file, Map<String, FontLocation> found) {
        String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);
        try {
            if (fileName.endsWith(".ttf")) {
                try (RandomAccessRead input = new RandomAccessReadBufferedFile(file.toFile())) {
                    register(file, new TTFParser().parseTableHeaders(input), false, found);
                }
            } else if (fileName.endsWith(".ttc")) {
                TrueTypeCollection.processAllFontHeaders(file.toFile(), headers -> register(file, headers, true, found));
            }
        } catch (IOException e) {
            log.debug("Police ignorée {} : {}", file, e.getMessage());
        }
    }

    private static void register(Path file, FontHeaders headers, boolean collection, Map<String, FontLocation> found) {
        String subFamily = headers.getFontSubFamily();
        if (headers.getError() != null || headers.getName() == null || subFamily == null
                || !PLAIN_STYLE.matcher(subFamily.trim()).matches()) {
            return;
        }
        String key = key(headers.getFontFamily() == null ? "" : headers.getFontFamily(),
                subFamily.contains("Bold"), subFamily.contains("Italic") || subFamily.contains("Oblique"));
        if (key != null) {
            found.putIfAbsent(key, new FontLocation(file, headers.getName(), collection));
        }
    }

    private SystemFont load(FontLocation location) {
        try {
            if (location.collection()) {
                try (TrueTypeCollection collection = new TrueTypeCollection(location.file().toFile())) {
                    return copy(location, collection.getFontByName(location.postScriptName()));
                }
            }
            try (TrueTypeFont font = new TTFParser().parse(new RandomAccessReadBuffer(Files.readAllBytes(location.file())))) {
                return copy(location, font);
            }
        } catch (IOException e) {
            log.warn("Impossible de charger la police système {} : {}", location.file(), e.getMessage());
            return null;
        }
    }

    /**
     * Recopie largeur et caractère de chaque glyphe : la police peut ensuite être refermée,
     * et le résultat partagé entre threads sans synchronisation.
     */
    private static SystemFont copy(FontLocation location, TrueTypeFont font) throws IOException {
        CmapLookup cmap = font == null ? null : font.getUnicodeCmapLookup(false);
        if (cmap == null) {
            return null;
        }
        int glyphs = font.getNumberOfGlyphs();
        float scale = 1000f / font.getUnitsPerEm();
        float[] widths = new float[glyphs];
        String[] unicode = new String[glyphs];
        for (int gid = 0; gid < glyphs; gid++) {
            widths[gid] = font.getAdvanceWidth(gid) * scale;
            unicode[gid] = unicodeOf(cmap, gid);
        }
        log.debug("Police système chargée : {} ({}, {} glyphes)", location.postScriptName(), location.file(), glyphs);
        return new SystemFont(location.postScriptName(), widths, unicode);
    }

    private static String unicodeOf(CmapLookup cmap, int gid) {
        List<Integer> codePoints = cmap.getCharCodes(gid);
        if (codePoints == null) {
            return null;
        }
        return codePoints.stream()
                .filter(cp -> cp >= 0x20)
                .min(Integer::compare)
                .map(Character::toString)
                .orElse(null);
    }
}

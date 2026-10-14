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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Index des polices TrueType installées dans app.anr.fonts-dir, retrouvées à partir du nom
 * d'une police PDF (« ABCDEF+TimesNewRoman,Bold », « Calibri-Bold-Identity-H », « ArialMT »...).
 * <p>
 * Le répertoire n'est parcouru qu'au premier besoin, et chaque police n'est chargée qu'une fois.
 * Sans app.anr.fonts-dir, aucune police n'est trouvée et la reconstitution est désactivée.
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

    private Map<String, FontLocation> locations;
    private final Map<String, Optional<SystemFont>> loaded = new HashMap<>();

    private record FontLocation(Path file, String postScriptName, boolean collection) {
    }

    /**
     * Police système installée, avec ce qu'il faut pour traduire un numéro de glyphe.
     *
     * @param name  Nom PostScript de la police
     * @param font  Police chargée
     * @param cmap  Table Unicode → glyphe de la police
     * @param scale Facteur de conversion des largeurs vers l'espace PDF (1000 unités par em)
     */
    public record SystemFont(String name, TrueTypeFont font, CmapLookup cmap, float scale) {

        /**
         * @return le caractère dessiné par ce glyphe, ou null si la police ne l'associe à aucun
         */
        public String unicodeOf(int gid) {
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

        /**
         * @return la largeur du glyphe en millièmes d'em, comme les largeurs déclarées dans un PDF
         */
        public float widthOf(int gid) throws IOException {
            return font.getAdvanceWidth(gid) * scale;
        }

        public int numberOfGlyphs() throws IOException {
            return font.getNumberOfGlyphs();
        }
    }

    /**
     * Cherche la police système correspondant à une police PDF (même famille, même graisse, même style).
     *
     * @param pdfFontName Nom de la police dans le PDF
     * @return la police installée, ou vide si aucune ne correspond
     */
    public synchronized Optional<SystemFont> find(String pdfFontName) {
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
        if (locations != null) {
            return locations;
        }
        locations = new HashMap<>();
        if (fontsDir == null || fontsDir.isBlank()) {
            log.info("app.anr.fonts-dir non renseigné : pas de reconstitution du texte via les polices système");
            return locations;
        }
        Path dir = Path.of(fontsDir);
        if (!Files.isDirectory(dir)) {
            log.warn("Répertoire de polices introuvable (app.anr.fonts-dir) : {}", fontsDir);
            return locations;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            files.filter(Files::isRegularFile).forEach(this::register);
        } catch (IOException e) {
            log.warn("Impossible de parcourir le répertoire de polices {} : {}", fontsDir, e.getMessage());
        }
        log.info("{} police(s) système indexée(s) dans {}", locations.size(), fontsDir);
        return locations;
    }

    private void register(Path file) {
        String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);
        try {
            if (fileName.endsWith(".ttf")) {
                try (RandomAccessRead input = new RandomAccessReadBufferedFile(file.toFile())) {
                    register(file, new TTFParser().parseTableHeaders(input), false);
                }
            } else if (fileName.endsWith(".ttc")) {
                TrueTypeCollection.processAllFontHeaders(file.toFile(), headers -> register(file, headers, true));
            }
        } catch (IOException e) {
            log.debug("Police ignorée {} : {}", file, e.getMessage());
        }
    }

    private void register(Path file, FontHeaders headers, boolean collection) {
        String subFamily = headers.getFontSubFamily();
        if (headers.getError() != null || headers.getName() == null || subFamily == null
                || !PLAIN_STYLE.matcher(subFamily.trim()).matches()) {
            return;
        }
        String key = key(headers.getFontFamily() == null ? "" : headers.getFontFamily(),
                subFamily.contains("Bold"), subFamily.contains("Italic") || subFamily.contains("Oblique"));
        if (key != null) {
            locations.putIfAbsent(key, new FontLocation(file, headers.getName(), collection));
        }
    }

    private SystemFont load(FontLocation location) {
        try {
            TrueTypeFont font;
            if (location.collection()) {
                // la collection reste ouverte : la police y lit ses tables à la demande
                try (InputStream input = Files.newInputStream(location.file())) {
                    font = new TrueTypeCollection(input).getFontByName(location.postScriptName());
                }
            } else {
                font = new TTFParser().parse(new RandomAccessReadBuffer(Files.readAllBytes(location.file())));
            }
            CmapLookup cmap = font == null ? null : font.getUnicodeCmapLookup(false);
            if (cmap == null) {
                return null;
            }
            log.debug("Police système chargée : {} ({})", location.postScriptName(), location.file());
            return new SystemFont(location.postScriptName(), font, cmap, 1000f / font.getUnitsPerEm());
        } catch (IOException e) {
            log.warn("Impossible de charger la police système {} : {}", location.file(), e.getMessage());
            return null;
        }
    }
}

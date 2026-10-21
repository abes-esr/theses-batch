package fr.theses.batch.job.processor;

import fr.theses.batch.business.anr.model.dto.ANRMatchDTO;
import fr.theses.batch.business.anr.model.dto.ANRPageMatchDTO;
import fr.theses.batch.util.log.PdfWarningCollector;
import fr.theses.batch.util.parser.PDFExtraction;
import fr.theses.batch.util.parser.PDFTextExtractor;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Processor pour le traitement ANR
 * Analyse les fichiers PDF à la recherche de motifs ANR
 */
@Slf4j
@Component("anrProcessor")
public class ANRProcessor implements ItemProcessor<ANRMatchDTO, ANRMatchDTO> {
    @Value("${app.anr.nb-pages:50}")
    private int maxPages;
    @Value("${app.anr.pattern:''}")
    private String anrPatternSource;
    @Value("${app.anr.context-characters:50}")
    private int contextCharacters;
    @Value("${app.anr.input-dir:/input}")
    private String inputDir;
    private final PDFTextExtractor pdfTextExtractor;
    private Pattern anrPattern;
    private Path inputPath;

    public ANRProcessor(PDFTextExtractor pdfTextExtractor) {
        this.pdfTextExtractor = pdfTextExtractor;
    }

    @PostConstruct
    public void init() {
        this.anrPattern = Pattern.compile(anrPatternSource);
        this.inputPath = Path.of(inputDir).toAbsolutePath().normalize();
    }

    /**
     * Au-delà de cette durée, un fichier sans rien d'autre à signaler est tout de même journalisé en
     * INFO : un fichier lent retient tout son chunk, les autres threads l'attendant pour finir.
     */
    static final Duration SLOW_FILE = Duration.ofSeconds(10);

    /**
     * Horloge en nanosecondes, remplaçable en test
     */
    private LongSupplier nanoClock = System::nanoTime;

    @Override
    public ANRMatchDTO process(ANRMatchDTO item) throws Exception {
        long startNanos = nanoClock.getAsLong();
        PdfWarningCollector.start();
        PDFExtraction extraction = null;

        try {
            String filePath = item.filePath();

            // Lire les pages du PDF
            extraction = pdfTextExtractor.extractTextFromPdf(filePath, maxPages);
            List<PDFTextExtractor.PDFPage> pages = extraction.pages();

            List<ANRPageMatchDTO> pageMatches = new ArrayList<>();

            // Traiter chaque page
            for (PDFTextExtractor.PDFPage page : pages) {
                String text = page.text();
                if (text == null || text.isEmpty()) {
                    continue;
                }

                // Rechercher les motifs ANR dans cette page
                Matcher matcher = anrPattern.matcher(text);
                while (matcher.find()) {
                    String matchValue = matcher.group();
                    int start = matcher.start();
                    int end = matcher.end();

                    // Extraire le contexte autour du match
                    String contextBefore = extractContext(text, start, contextCharacters, true);
                    String contextAfter = extractContext(text, end, contextCharacters, false);

                    ANRPageMatchDTO pageMatch = new ANRPageMatchDTO(page.pageNumber(),matchValue, contextBefore, contextAfter);
                    pageMatches.add(pageMatch);
                }
            }

            // Si aucun match trouvé, retourner null pour ne pas écrire dans le CSV
            if (pageMatches.isEmpty()) {
                return null;
            }

            // Mettre à jour le DTO
            return item
                    .withPageMatches(pageMatches)
                    .withPagesAnalyzed(pages.size())
                    .withTotalPages(extraction.totalPages())
                    .withProcessingTime(seconds(nanoClock.getAsLong() - startNanos));

        } catch (Exception e) {
            // Capturer TOUTE exception (PDF malformé = problème de données, pas bug)
            // Retourner un DTO avec error_message au lieu de propager
            String filePath = item.filePath();
            log.warn("Erreur lors du traitement du fichier {} (PDF malformé) : {}",
                   relativeToInputDir(filePath), e.getMessage(), e);
            return item
                    .withProcessingTime(seconds(nanoClock.getAsLong() - startNanos))
                    .withErrorMessage("Erreur PDF: " + e.getMessage());
        } finally {
            logExtractionSummary(relativeToInputDir(item.filePath()), extraction,
                    nanoClock.getAsLong() - startNanos);
        }
    }

    /**
     * Durée en secondes, à la milliseconde près
     */
    private static double seconds(long nanos) {
        return Math.round(nanos / 1e6) / 1000.0;
    }

    /**
     * Chemin du fichier relatif à app.anr.input-dir, plus lisible dans les logs que le chemin absolu.
     *
     * @param filePath Chemin du fichier PDF
     * @return Chemin relatif, ou le chemin d'origine s'il n'est pas sous app.anr.input-dir
     */
    private String relativeToInputDir(String filePath) {
        Path path = Path.of(filePath).toAbsolutePath().normalize();
        return path.startsWith(inputPath) ? inputPath.relativize(path).toString() : filePath;
    }

    /**
     * Journalise en un seul message le coût et la qualité de l'extraction de ce fichier : durée de
     * traitement, taille, pages lues sur le total, part du texte perdu (glyphes sans caractère
     * Unicode), part reconstituée via les polices système, et avertissements PDFBox / FontBox
     * regroupés par type, triés par nombre décroissant.
     * Texte perdu ou avertissement : WARN. Texte reconstitué, ou fichier plus lent que SLOW_FILE :
     * INFO. Sinon DEBUG (--logging.level.fr.theses.batch.job.processor.ANRProcessor=DEBUG pour avoir
     * la durée et la taille de chaque fichier).
     *
     * @param relativePath Chemin du fichier relatif à app.anr.input-dir
     * @param extraction   Résultat de l'extraction, null si le fichier n'a pas pu être lu
     * @param elapsedNanos Durée de traitement du fichier
     */
    private void logExtractionSummary(String relativePath, PDFExtraction extraction, long elapsedNanos) {
        Map<String, Integer> warnings = PdfWarningCollector.stop();
        boolean lost = extraction != null && extraction.lostGlyphs() > 0;
        boolean recovered = extraction != null && extraction.recoveredGlyphs() > 0;
        boolean slow = elapsedNanos >= SLOW_FILE.toNanos();
        if (warnings.isEmpty() && !lost && !recovered && !slow && !log.isDebugEnabled()) {
            return;
        }

        StringBuilder message = new StringBuilder(relativePath)
                .append(String.format(Locale.FRANCE, " (%.1f s", elapsedNanos / 1e9));
        if (extraction != null) {
            message.append(String.format(Locale.FRANCE, ", %.1f Mo, %d/%d pages",
                    extraction.fileSize() / 1_048_576.0, extraction.pages().size(), extraction.totalPages()));
        }
        message.append(')');
        if (extraction != null) {
            message.append(String.format(Locale.FRANCE, " : texte perdu %s (%d/%d glyphes)",
                    formatPercent(extraction.lostPercent()), extraction.lostGlyphs(), extraction.glyphs()));
        }
        if (recovered) {
            message.append(", reconstitué ").append(formatPercent(extraction.recoveredPercent()))
                    .append(" via les polices système");
        }
        if (!warnings.isEmpty()) {
            int total = warnings.values().stream().mapToInt(Integer::intValue).sum();
            message.append(", ").append(total).append(" avertissement(s) PDFBox :");
            warnings.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .forEach(e -> message.append(String.format("%n    %5d x %s", e.getValue(), e.getKey())));
        }

        if (lost || !warnings.isEmpty()) {
            log.warn("{}", message);
        } else if (recovered || slow) {
            log.info("{}", message);
        } else {
            log.debug("{}", message);
        }
    }

    /**
     * Pourcentage à une décimale ; une part non nulle mais inférieure à 0,1 % s'affiche « < 0,1 % »
     * plutôt que « 0,0 % ».
     */
    private static String formatPercent(double percent) {
        return percent > 0 && percent < 0.05 ? "< 0,1 %" : String.format(Locale.FRANCE, "%.1f %%", percent);
    }

    /**
     * Extrait le contexte autour d'une position dans le texte
     *
     * @param text Texte complet
     * @param position Position de référence
     * @param length Nombre de caractères à extraire
     * @param before Si true, extrait avant la position, sinon après
     * @return Texte du contexte
     */
    private String extractContext(String text, int position, int length, boolean before) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        int start = before ? Math.max(0, position - length) : position;
        int end = before ? position : Math.min(text.length(), position + length);

        // Ne pas couper une paire de surrogates (caractère hors BMP, ex. 𝑥 U+1D465) : une demi-paire
        // isolée n'est pas encodable en UTF-8. Le caractère coupé est laissé hors du contexte.
        if (start > 0 && start < end && Character.isLowSurrogate(text.charAt(start))
                && Character.isHighSurrogate(text.charAt(start - 1))) {
            start++;
        }
        if (end < text.length() && end > start && Character.isLowSurrogate(text.charAt(end))
                && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }

        return text.substring(start, end);
    }
}

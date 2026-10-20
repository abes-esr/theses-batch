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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    @Override
    public ANRMatchDTO process(ANRMatchDTO item) throws Exception {
        Instant startTime = Instant.now();
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

            // Calculer la durée
            Duration processingDuration = Duration.between(startTime, Instant.now());

            // Mettre à jour le DTO
            return item
                    .withPageMatches(pageMatches)
                    .withPagesAnalyzed(pages.size())
                    .withTotalPages(extraction.totalPages())
                    .withProcessingTime(processingDuration.toMillis() / 1000.0);

        } catch (Exception e) {
            // Capturer TOUTE exception (PDF malformé = problème de données, pas bug)
            // Retourner un DTO avec error_message au lieu de propager
            Duration processingDuration = Duration.between(startTime, Instant.now());
            String filePath = item.filePath();
            log.warn("Erreur lors du traitement du fichier {} (PDF malformé) : {}", 
                   relativeToInputDir(filePath), e.getMessage(), e);
            return item
                    .withProcessingTime(processingDuration.toMillis() / 1000.0)
                    .withErrorMessage("Erreur PDF: " + e.getMessage());
        } finally {
            logExtractionSummary(relativeToInputDir(item.filePath()), extraction);
        }
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
     * Journalise en un seul message la qualité de l'extraction de ce fichier : part du texte perdu
     * (glyphes sans caractère Unicode), part reconstituée via les polices système, et avertissements
     * PDFBox / FontBox regroupés par type, triés par nombre décroissant.
     * Rien n'est écrit si l'extraction est complète et sans avertissement ; un fichier dont le texte a été
     * entièrement reconstitué est signalé en INFO.
     *
     * @param relativePath Chemin du fichier relatif à app.anr.input-dir
     * @param extraction   Résultat de l'extraction, null si le fichier n'a pas pu être lu
     */
    private void logExtractionSummary(String relativePath, PDFExtraction extraction) {
        Map<String, Integer> warnings = PdfWarningCollector.stop();
        boolean lost = extraction != null && extraction.lostGlyphs() > 0;
        boolean recovered = extraction != null && extraction.recoveredGlyphs() > 0;
        if (warnings.isEmpty() && !lost && !recovered) {
            return;
        }

        StringBuilder message = new StringBuilder(relativePath);
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
        } else {
            log.info("{}", message);
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

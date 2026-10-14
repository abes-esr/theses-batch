package fr.theses.batch.job.processor;

import fr.theses.batch.business.anr.model.dto.ANRMatchDTO;
import fr.theses.batch.business.anr.model.dto.ANRPageMatchDTO;
import fr.theses.batch.util.log.PdfWarningCollector;
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
    @Value("${app.anr.input-dir}")
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

        try {
            String filePath = item.filePath();

            // Lire les pages du PDF
            List<PDFTextExtractor.PDFPage> pages = pdfTextExtractor.extractTextFromPdf(filePath, maxPages);

            List<ANRPageMatchDTO> pageMatches = new ArrayList<>();

            // Traiter chaque page
            for (PDFTextExtractor.PDFPage page : pages) {
                String text = page.getText();
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

                    ANRPageMatchDTO pageMatch = new ANRPageMatchDTO(page.getPageNumber(), matchValue, contextBefore, contextAfter);
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
                    .withTotalPages(pdfTextExtractor.getPageCount(filePath))
                    .withProcessingTime(processingDuration.toMillis() / 1000.0);

        } catch (IOException e) {
            Duration processingDuration = Duration.between(startTime, Instant.now());
            return item
                    .withProcessingTime(processingDuration.toMillis() / 1000.0)
                    .withErrorMessage("Erreur lors du traitement du fichier: " + e.getMessage());
        } finally {
            logPdfWarnings(relativeToInputDir(item.filePath()));
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
     * Journalise en un seul message les avertissements PDFBox / FontBox collectés pour ce fichier,
     * regroupés par type et triés par nombre décroissant.
     *
     * @param fileName Nom du fichier traité
     */
    private void logPdfWarnings(String relativePath) {
        Map<String, Integer> warnings = PdfWarningCollector.stop();
        if (warnings.isEmpty()) {
            return;
        }

        int total = warnings.values().stream().mapToInt(Integer::intValue).sum();
        StringBuilder detail = new StringBuilder();
        warnings.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> detail.append(String.format("%n    %5d x %s", e.getValue(), e.getKey())));

        log.warn("{} avertissement(s) PDFBox pour {} :{}", total, relativePath, detail);
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

        return text.substring(start, end);
    }
}

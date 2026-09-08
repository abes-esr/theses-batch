package fr.theses.batch.job.processor;

import fr.theses.batch.business.anr.model.dto.ANRMatchDTO;
import fr.theses.batch.business.anr.model.dto.ANRPageMatchDTO;
import fr.theses.batch.business.anr.service.ANRSearchService;
import fr.theses.batch.util.parser.PDFTextExtractor;
import jakarta.annotation.PostConstruct;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Processor pour le traitement ANR
 * Analyse les fichiers PDF à la recherche de motifs ANR
 */
@Component("anrProcessor")
public class ANRProcessor implements ItemProcessor<ANRMatchDTO, ANRMatchDTO> {
    @Value("${app.anr.nb-pages:50}")
    private int maxPages;
    @Value("${app.anr.pattern:''}")
    private String anrPatternSource;
    @Value("${app.anr.context-characters:50}")
    private int contextCharacters;
    private final ANRSearchService anrSearchService;
    private final PDFTextExtractor pdfTextExtractor;
    private Pattern anrPattern;

    public ANRProcessor(ANRSearchService anrSearchService,
                        PDFTextExtractor pdfTextExtractor
                        ) {
        this.anrSearchService = anrSearchService;
        this.pdfTextExtractor = pdfTextExtractor;
    }

    @PostConstruct
    public void init() {
        this.anrPattern = Pattern.compile(anrPatternSource);
    }
    
    @Override
    public ANRMatchDTO process(ANRMatchDTO item) throws Exception {
        Instant startTime = Instant.now();
        
        try {
            String filePath = item.filePath();
            
            // Lire les pages du PDF
            List<PDFTextExtractor.PDFPage> pages = pdfTextExtractor.extractTextFromPdf(filePath, maxPages);
            
            List<ANRPageMatchDTO> pageMatches = new ArrayList<>();
            int totalPages = pages.size();
            
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
        }
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

package fr.theses.batch.job.processor;

import fr.theses.batch.business.anr.model.dto.ANRMatchDTO;
import fr.theses.batch.business.anr.model.dto.ANRPageMatchDTO;
import fr.theses.batch.business.anr.service.ANRSearchService;
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
    
    private static final int CONTEXT_CHARACTERS = 50;
    
    private final ANRSearchService anrSearchService;
    private final PDFProcessingService pdfProcessingService;
    private final int maxPages;
    private final Pattern anrPattern;
    
    public ANRProcessor(ANRSearchService anrSearchService,
                        PDFProcessingService pdfProcessingService,
                        @Value("${app.anr.nb-pages:0}") int maxPages,
                        @Value("${app.anr.pattern}") String anrPattern) {
        this.anrSearchService = anrSearchService;
        this.pdfProcessingService = pdfProcessingService;
        this.maxPages = maxPages;
        this.anrPattern = Pattern.compile(anrPattern);
    }
    
    @Override
    public ANRMatchDTO process(ANRMatchDTO item) throws Exception {
        Instant startTime = Instant.now();
        
        try {
            String filePath = item.getFilePath();
            
            // Lire les pages du PDF
            List<PDFProcessingService.PDFPage> pages = pdfProcessingService.extractTextFromPdf(filePath, maxPages);
            
            List<ANRPageMatchDTO> pageMatches = new ArrayList<>();
            int totalPages = pages.size();
            
            // Traiter chaque page
            for (PDFProcessingService.PDFPage page : pages) {
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
                    String contextBefore = extractContext(text, start, CONTEXT_CHARACTERS, true);
                    String contextAfter = extractContext(text, end, CONTEXT_CHARACTERS, false);
                    
                    ANRPageMatchDTO pageMatch = new ANRPageMatchDTO();
                    pageMatch.setPageNumber(page.getPageNumber());
                    pageMatch.setMatchValue(matchValue);
                    pageMatch.setContextBefore(contextBefore);
                    pageMatch.setContextAfter(contextAfter);
                    pageMatches.add(pageMatch);
                }
            }
            
            // Mettre à jour le DTO
            item.setPageMatches(pageMatches);
            item.setPagesAnalyzed(pages.size());
            item.setTotalPages(pdfProcessingService.getPageCount(filePath));
            
            // Calculer la durée
            Duration processingDuration = Duration.between(startTime, Instant.now());
            item.setProcessingTime(processingDuration.toMillis() / 1000.0);
            
            return item;
            
        } catch (IOException e) {
            Duration processingDuration = Duration.between(startTime, Instant.now());
            item.setProcessingTime(processingDuration.toMillis() / 1000.0);
            item.setErrorMessage("Erreur lors du traitement du fichier: " + e.getMessage());
            return item;
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

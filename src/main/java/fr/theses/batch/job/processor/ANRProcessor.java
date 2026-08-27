package fr.theses.batch.job.processor;

import fr.theses.batch.business.anr.model.dto.ANRMatchDTO;
import fr.theses.batch.business.anr.service.ANRSearchService;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Processor pour le traitement ANR
 * Analyse les fichiers PDF à la recherche de motifs ANR
 */
@Component("anrProcessor")
public class ANRProcessor implements ItemProcessor<ANRMatchDTO, ANRMatchDTO> {
    
    private final ANRSearchService anrSearchService;
    private final PDFProcessingService pdfProcessingService;
    private final int maxPages;
    
    public ANRProcessor(ANRSearchService anrSearchService,
                        PDFProcessingService pdfProcessingService,
                        @Value("${app.nb-pages:0}") int maxPages) {
        this.anrSearchService = anrSearchService;
        this.pdfProcessingService = pdfProcessingService;
        this.maxPages = maxPages;
    }
    
    @Override
    public ANRMatchDTO process(ANRMatchDTO item) throws Exception {
        Instant startTime = Instant.now();
        
        try {
            String filePath = item.getFilePath();
            
            // Lire le contenu du PDF
            String pdfText = pdfProcessingService.extractTextFromPdf(filePath, maxPages);
            
            // Rechercher les motifs ANR
            List<String> matches = anrSearchService.findANRMatches(pdfText);
            
            // Mettre à jour le DTO
            item.setMatches(matches);
            
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
}

package fr.theses.batch.job.writer;

import fr.theses.batch.business.anr.model.dto.ANRMatchDTO;
import fr.theses.batch.business.anr.model.dto.ANRPageMatchDTO;
import fr.theses.batch.business.anr.model.entity.ANRMatch;
import fr.theses.batch.business.anr.service.ANRSearchService;
import jakarta.persistence.EntityManager;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Writer pour le traitement ANR
 * Écrit les résultats dans des fichiers CSV et en base de données
 */
@Component("anrWriter")
public class ANRWriter implements ItemWriter<ANRMatchDTO> {
    
    private final EntityManager entityManager;
    private final ANRSearchService anrSearchService;
    private final String outputDir;
    
    public ANRWriter(EntityManager entityManager,
                     ANRSearchService anrSearchService,
                     @Value("${app.output-dir}") String outputDir) {
        this.entityManager = entityManager;
        this.anrSearchService = anrSearchService;
        this.outputDir = outputDir;
    }
    
    @Override
    public void write(Chunk<? extends ANRMatchDTO> chunk) throws Exception {
        // Créer le répertoire de sortie si nécessaire
        Path outputPath = Path.of(outputDir);
        if (!Files.exists(outputPath)) {
            Files.createDirectories(outputPath);
        }
        
        // Écrire dans le fichier CSV
        writeToCsv(chunk.getItems());
        
        // Écrire en base de données
        writeToDatabase(chunk.getItems());
    }
    
    /**
     * Écrit les résultats dans un fichier CSV
     * 
     * @param items Liste des DTO à écrire
     * @throws IOException En cas d'erreur d'écriture
     */
    private void writeToCsv(List<? extends ANRMatchDTO> items) throws IOException {
        String timestamp = LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss"));
        String csvFileName = outputDir + "/" + timestamp + "_anr_results.csv";
        
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(csvFileName, true))) {
            // Écrire l'en-tête si le fichier est vide
            if (Files.size(Path.of(csvFileName)) == 0) {
                writer.write("file_path,file_name,page_number,match_value,context_before,context_after,pages_analyzed,total_pages,processing_time,error_message\n");
            }
            
            for (ANRMatchDTO item : items) {
                String filePath = item.getFilePath();
                String fileName = item.getFileName();
                String errorMessage = item.getErrorMessage();
                
                if (item.getPageMatches().isEmpty() && (errorMessage == null || errorMessage.isEmpty())) {
                    // Écrire une ligne même sans correspondances
                    String line = String.format("\"%s\",\"%s\",\"\",\"\",\"\",\"\",%d,%d,%.2f,"%s\"\n",
                            escapeCsv(filePath),
                            escapeCsv(fileName),
                            item.getPagesAnalyzed(),
                            item.getTotalPages(),
                            item.getProcessingTime(),
                            escapeCsv(errorMessage != null ? errorMessage : ""));
                    writer.write(line);
                } else {
                    // Écrire une ligne par correspondance avec page
                    for (ANRPageMatchDTO pageMatch : item.getPageMatches()) {
                        String line = String.format("\"%s\",\"%s\",%d,"%s","%s","%s",%d,%d,%.2f,"\"\n",
                                escapeCsv(filePath),
                                escapeCsv(fileName),
                                pageMatch.getPageNumber(),
                                escapeCsv(pageMatch.getMatchValue()),
                                escapeCsv(pageMatch.getContextBefore()),
                                escapeCsv(pageMatch.getContextAfter()),
                                item.getPagesAnalyzed(),
                                item.getTotalPages(),
                                item.getProcessingTime());
                        writer.write(line);
                    }
                }
                
                // Si erreur, écrire une ligne d'erreur
                if (errorMessage != null && !errorMessage.isEmpty()) {
                    String line = String.format("\"%s\",\"%s\",\"\",\"\",\"\",\"\",%d,%d,%.2f,"%s\"\n",
                            escapeCsv(filePath),
                            escapeCsv(fileName),
                            item.getPagesAnalyzed(),
                            item.getTotalPages(),
                            item.getProcessingTime(),
                            escapeCsv(errorMessage));
                    writer.write(line);
                }
            }
        }
    }
    
    /**
     * Écrit les résultats en base de données
     * 
     * @param items Liste des DTO à écrire
     */
    private void writeToDatabase(List<? extends ANRMatchDTO> items) {
        for (ANRMatchDTO item : items) {
            String filePath = item.getFilePath();
            String fileName = item.getFileName();
            
            for (ANRPageMatchDTO pageMatch : item.getPageMatches()) {
                ANRMatch entity = new ANRMatch();
                entity.setFilePath(filePath);
                entity.setFileName(fileName);
                entity.setMatchValue(pageMatch.getMatchValue());
                entity.setProcessingDate(LocalDateTime.now());
                entity.setPageNumber(pageMatch.getPageNumber());
                
                entityManager.persist(entity);
            }
        }
        entityManager.flush();
    }
    
    /**
     * Échappe les caractères spéciaux pour le CSV
     * 
     * @param value Valeur à échapper
     * @return Valeur échappée
     */
    private String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\"", "\"\"")
                     .replace("\n", " ")
                     .replace("\r", " ");
    }
}

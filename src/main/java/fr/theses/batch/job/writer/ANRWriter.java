package fr.theses.batch.job.writer;

import fr.theses.batch.business.anr.model.dto.ANRMatchDTO;
import fr.theses.batch.business.anr.model.dto.ANRPageMatchDTO;
import fr.theses.batch.business.anr.model.entity.ANRMatch;
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
    private final String outputDir;

    public ANRWriter(EntityManager entityManager,
                     @Value("${app.anr.output-dir:'/output'}") String outputDir) {
        this.entityManager = entityManager;
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
                String filePath = item.filePath();
                String fileName = item.fileName();
                String errorMessage = item.errorMessage();
                
                if (item.pageMatches().isEmpty() && (errorMessage == null || errorMessage.isEmpty())) {
                    // Écrire une ligne même sans correspondances
                    String line = String.format("\"%s\",\"%s\",\"\",\"\",\"\",\"\",%d,%d,%.2f,\"%s\"\n",
                            escapeCsv(filePath),
                            escapeCsv(fileName),
                            item.pagesAnalyzed(),
                            item.totalPages(),
                            item.processingTime(),
                            escapeCsv(errorMessage != null ? errorMessage : ""));
                    writer.write(line);
                } else {
                    // Écrire une ligne par correspondance avec page
                    for (ANRPageMatchDTO pageMatch : item.pageMatches()) {
                        String line = String.format("\"%s\",\"%s\",%d,\"%s\",\"%s\",\"%s\",%d,%d,%.2f,\"\"\n",
                                escapeCsv(filePath),
                                escapeCsv(fileName),
                                pageMatch.pageNumber(),
                                escapeCsv(pageMatch.matchValue()),
                                escapeCsv(pageMatch.contextBefore()),
                                escapeCsv(pageMatch.contextAfter()),
                                item.pagesAnalyzed(),
                                item.totalPages(),
                                item.processingTime());
                        writer.write(line);
                    }
                }
                
                // Si erreur, écrire une ligne d'erreur
                if (errorMessage != null && !errorMessage.isEmpty()) {
                    String line = String.format("\"%s\",\"%s\",\"\",\"\",\"\",\"\",%d,%d,%.2f,\"%s\"\n",
                            escapeCsv(filePath),
                            escapeCsv(fileName),
                            item.pagesAnalyzed(),
                            item.totalPages(),
                            item.processingTime(),
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
            String filePath = item.filePath();
            String fileName = item.fileName();
            
            for (ANRPageMatchDTO pageMatch : item.pageMatches()) {
                ANRMatch entity = new ANRMatch();
                entity.setFilePath(filePath);
                entity.setFileName(fileName);
                entity.setMatchValue(pageMatch.matchValue());
                entity.setProcessingDate(LocalDateTime.now());
                entity.setPageNumber(pageMatch.pageNumber());
                
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

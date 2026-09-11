package fr.theses.batch.job.writer;

import fr.theses.batch.business.anr.model.dto.ANRMatchDTO;
import fr.theses.batch.business.anr.model.dto.ANRPageMatchDTO;
import fr.theses.batch.business.anr.model.entity.ANRMatch;
import jakarta.persistence.EntityManager;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
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
        Path outputPath = Path.of(outputDir);
        if (!Files.exists(outputPath)) {
            Files.createDirectories(outputPath);
        }
        
        writeToCsv(chunk.getItems());
        writeToDatabase(chunk.getItems());
    }
    
    private void writeToCsv(List<? extends ANRMatchDTO> items) throws IOException {
        String timestamp = LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss"));
        String csvFileName = outputDir + "/" + timestamp + "_anr_results.csv";
        
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(csvFileName, true))) {
            if (Files.size(Path.of(csvFileName)) == 0) {
                writer.write("file_path,file_name,page_number,match_value,context_before,context_after,pages_analyzed,total_pages,processing_time,error_message,nnt,doi,defense_date\n");
            }
            
            for (ANRMatchDTO item : items) {
                String filePath = item.filePath();
                String fileName = item.fileName();
                String errorMessage = item.errorMessage();
                String nnt = item.nnt();
                String doi = item.doi();
                String defenseDate = item.defenseDate();
                
                if (item.pageMatches().isEmpty() && (errorMessage == null || errorMessage.isEmpty())) {
                    String line = String.format("\"%s\",\"%s\",\"\",\"\",\"\",\"\",%d,%d,%.2f,\"%s\",\"%s\",\"%s\",\"%s\"\n",
                            escapeCsv(filePath),
                            escapeCsv(fileName),
                            item.pagesAnalyzed(),
                            item.totalPages(),
                            item.processingTime(),
                            escapeCsv(errorMessage != null ? errorMessage : ""),
                            escapeCsv(nnt != null ? nnt : ""),
                            escapeCsv(doi != null ? doi : ""),
                            escapeCsv(defenseDate != null ? defenseDate : ""));
                    writer.write(line);
                } else {
                    for (ANRPageMatchDTO pageMatch : item.pageMatches()) {
                        String line = String.format("\"%s\",\"%s\",%d,\"%s\",\"%s\",\"%s\",%d,%d,%.2f,\"%s\",\"%s\",\"%s\",\"%s\"\n",
                                escapeCsv(filePath),
                                escapeCsv(fileName),
                                pageMatch.pageNumber(),
                                escapeCsv(pageMatch.matchValue()),
                                escapeCsv(pageMatch.contextBefore()),
                                escapeCsv(pageMatch.contextAfter()),
                                item.pagesAnalyzed(),
                                item.totalPages(),
                                item.processingTime(),
                                escapeCsv(""),
                                escapeCsv(nnt != null ? nnt : ""),
                                escapeCsv(doi != null ? doi : ""),
                                escapeCsv(defenseDate != null ? defenseDate : ""));
                        writer.write(line);
                    }
                }
                
                if (errorMessage != null && !errorMessage.isEmpty()) {
                    String line = String.format("\"%s\",\"%s\",\"\",\"\",\"\",\"\",%d,%d,%.2f,\"%s\",\"%s\",\"%s\",\"%s\"\n",
                            escapeCsv(filePath),
                            escapeCsv(fileName),
                            item.pagesAnalyzed(),
                            item.totalPages(),
                            item.processingTime(),
                            escapeCsv(errorMessage),
                            escapeCsv(nnt != null ? nnt : ""),
                            escapeCsv(doi != null ? doi : ""),
                            escapeCsv(defenseDate != null ? defenseDate : ""));
                    writer.write(line);
                }
            }
        }
    }
    
    private void writeToDatabase(List<? extends ANRMatchDTO> items) {
        for (ANRMatchDTO item : items) {
            String filePath = item.filePath();
            String fileName = item.fileName();
            String nnt = item.nnt();
            String doi = item.doi();
            String defenseDate = item.defenseDate();
            
            for (ANRPageMatchDTO pageMatch : item.pageMatches()) {
                ANRMatch entity = new ANRMatch();
                entity.setFilePath(filePath);
                entity.setFileName(fileName);
                entity.setMatchValue(pageMatch.matchValue());
                entity.setProcessingDate(LocalDateTime.now());
                entity.setPageNumber(pageMatch.pageNumber());
                entity.setNnt(nnt);
                entity.setDoi(doi);
                entity.setDefenseDate(defenseDate);
                
                entityManager.persist(entity);
            }
        }
        entityManager.flush();
    }
    
    private String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\"", "\"\"")
                     .replace("\n", " ")
                     .replace("\r", " ");
    }
}

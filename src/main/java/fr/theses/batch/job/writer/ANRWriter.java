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
import java.util.*;
import java.util.stream.Collectors;


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

        // Trier par année de soutenance
        List<ANRMatchDTO> sortedItems = chunk.getItems().stream()
                .sorted(Comparator.comparing(this::extractYear))
                .collect(Collectors.toList());

        writeToCsv(sortedItems);
        writeToDatabase(sortedItems);
    }

    /**
     * Extrait l'année de la date de soutenance
     *
     * @param item L'item ANR
     * @return L'année ou "unknown" si la date n'est pas disponible
     */
    private String extractYear(ANRMatchDTO item) {
        String defenseDate = item.defenseDate();
        if (defenseDate == null || defenseDate.isEmpty()) {
            return "unknown";
        }
        // Chercher un pattern d'année (4 chiffres)
        if (defenseDate.length() >= 4 && defenseDate.matches(".*\\d{4}.*")) {
            return defenseDate.substring(0, 4);
        }
        return "unknown";
    }

    private void writeToCsv(List<? extends ANRMatchDTO> items) throws IOException {
        // Grouper les items par année
        Map<String, List<ANRMatchDTO>> itemsByYear = items.stream()
                .collect(Collectors.groupingBy(this::extractYear));

        // Créer un fichier par année
        for (Map.Entry<String, List<ANRMatchDTO>> entry : itemsByYear.entrySet()) {
            String year = entry.getKey();
            List<ANRMatchDTO> yearItems = entry.getValue();

            String csvFileName = outputDir + "/anr_results_" + year + ".csv";
            boolean fileExists = Files.exists(Path.of(csvFileName));

            try (BufferedWriter writer = new BufferedWriter(new FileWriter(csvFileName, true))) {
                // Écrire l'en-tête seulement si le fichier est nouveau
                if (!fileExists) {
                    writer.write("file_path,file_name,page_number,match_value,context_before,context_after,pages_analyzed,total_pages,processing_time,error_message,nnt,doi,defense_date\n");
                }

                for (ANRMatchDTO item : yearItems) {
                    writeItemToCsv(writer, item);
                }
            }
        }
    }

    private void writeItemToCsv(BufferedWriter writer, ANRMatchDTO item) throws IOException {
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
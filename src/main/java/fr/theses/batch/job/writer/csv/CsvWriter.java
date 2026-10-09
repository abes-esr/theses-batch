package fr.theses.batch.job.writer.csv;

import fr.theses.batch.business.anr.model.dto.ANRMatchDTO;
import fr.theses.batch.business.anr.model.dto.ANRPageMatchDTO;
import fr.theses.batch.job.writer.ANRFormatWriter;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Writer pour l'export CSV des résultats ANR
 * Permet une configuration personnalisable des en-têtes via properties
 */
@Component
public class CsvWriter implements ANRFormatWriter {

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss");

    private static final String QUOTE = "\"";
    private static final String COMMA = ",";
    private static final String NEWLINE = "\n";

    private final String outputDir;
    private final String csvHeader;

    public CsvWriter(
            @Value("${app.anr.output-dir:'/output'}") String outputDir,
            @Value("${app.anr.csv.header:file_path,file_name,page_number,match_value,context_before,context_after,pages_analyzed,total_pages,processing_time,error_message,nnt,doi,defense_date}")
            String csvHeader) {
        this.outputDir = outputDir;
        this.csvHeader = csvHeader;
    }

    @Override
    public void write(Chunk<? extends ANRMatchDTO> chunk) throws Exception {
        write(chunk.getItems());
    }

    @Override
    public void write(List<? extends ANRMatchDTO> items) throws IOException {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMATTER);

        // Grouper les items par année de soutenance
        Map<String, List<ANRMatchDTO>> itemsByYear = items.stream()
                .collect(Collectors.groupingBy(this::extractYear));

        // Créer un fichier par année
        for (Map.Entry<String, List<ANRMatchDTO>> entry : itemsByYear.entrySet()) {
            String year = entry.getKey();
            List<ANRMatchDTO> yearItems = entry.getValue();
            Path csvFilePath = Path.of(outputDir, timestamp + "_anr_results_" + year + ".csv");

            if (!Files.exists(csvFilePath.getParent())) {
                Files.createDirectories(csvFilePath.getParent());
            }

            try (BufferedWriter writer = new BufferedWriter(new FileWriter(csvFilePath.toFile(), true))) {
                if (Files.size(csvFilePath) == 0) {
                    writer.write(csvHeader + NEWLINE);
                }

                for (ANRMatchDTO item : yearItems) {
                    writeItemToCsv(writer, item);
                }
            }
        }
    }

    /**
     * Extrait l'année de la date de soutenance
     *
     * @param item L'item ANR
     * @return L'année ou "unknown" si la date n'est pas disponible
     */
    private String extractYear(ANRMatchDTO item) {
        if (item.defenseDate() == null) {
            return "unknown";
        }
        return String.valueOf(item.defenseDate().getYear());
    }

    private void writeItemToCsv(BufferedWriter writer, ANRMatchDTO item) throws IOException {
        String filePath = item.filePath();
        String fileName = item.fileName();
        String errorMessage = item.errorMessage();
        String nnt = item.nnt();
        String doi = item.doi();
        String defenseDate = String.valueOf(item.defenseDate());

        if (item.pageMatches().isEmpty() && (errorMessage == null || errorMessage.isEmpty())) {
            String line = String.format("\"%s\",\"%s\",\"\",\"\",\"\",\"\",%d,%d,%.2f,\"%s\",\"%s\",\"%s\",\"%s\"\n",
                    escapeCsv(filePath),
                    escapeCsv(fileName),
                    item.pagesAnalyzed(),
                    item.totalPages(),
                    item.processingTime(),
                    escapeCsv(errorMessage),
                    escapeCsv(nnt),
                    escapeCsv(doi),
                    escapeCsv(defenseDate));
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
                        escapeCsv(nnt),
                        escapeCsv(doi),
                        escapeCsv(defenseDate));
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
                    escapeCsv(nnt),
                    escapeCsv(doi),
                    escapeCsv(defenseDate));
            writer.write(line);
        }
    }

    private String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        return value.replace(QUOTE, QUOTE + QUOTE)
                .replace("\n", " ")
                .replace("\r", " ");
    }
}

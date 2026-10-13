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
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Writer pour l'export CSV des résultats ANR
 * Permet une configuration personnalisable des en-têtes via properties
 */
@Component
public class CsvWriter implements ANRFormatWriter {

    /**
     * Enum définissant les champs possibles et leurs extracteurs de valeurs
     */
    private enum CsvField {
        FILE_PATH("file_path", true, item -> item.filePath(), (item, page) -> item.filePath()),
        FILE_NAME("file_name", true, item -> item.fileName(), (item, page) -> item.fileName()),
        PAGE_NUMBER("page_number", false, item -> "", (item, page) -> String.valueOf(page.pageNumber())),
        MATCH_VALUE("match_value", true, item -> "", (item, page) -> page.matchValue()),
        CONTEXT_BEFORE("context_before", true, item -> "", (item, page) -> page.contextBefore()),
        CONTEXT_AFTER("context_after", true, item -> "", (item, page) -> page.contextAfter()),
        PAGES_ANALYZED("pages_analyzed", false, item -> String.valueOf(item.pagesAnalyzed()), (item, page) -> String.valueOf(item.pagesAnalyzed())),
        TOTAL_PAGES("total_pages", false, item -> String.valueOf(item.totalPages()), (item, page) -> String.valueOf(item.totalPages())),
        PROCESSING_TIME("processing_time", false, item -> String.format(Locale.US, "%.3f", item.processingTime()), (item, page) -> String.format(Locale.US, "%.3f", item.processingTime())),
        ERROR_MESSAGE("error_message", true, item -> item.errorMessage() != null ? item.errorMessage() : "", (item, page) -> ""),
        NNT("nnt", true, item -> item.nnt() != null ? item.nnt() : "", (item, page) -> item.nnt() != null ? item.nnt() : ""),
        DOI("doi", true, item -> item.doi() != null ? item.doi() : "", (item, page) -> item.doi() != null ? item.doi() : ""),
        DEFENSE_DATE("defense_date", true, item -> item.defenseDate() != null ? String.valueOf(item.defenseDate()) : "", (item, page) -> item.defenseDate() != null ? String.valueOf(item.defenseDate()) : "");

        private final String name;
        private final boolean needsEscape;
        private final Function<ANRMatchDTO, String> itemExtractor;
        private final java.util.function.BiFunction<ANRMatchDTO, ANRPageMatchDTO, String> pageExtractor;

        CsvField(String name, boolean needsEscape, Function<ANRMatchDTO, String> itemExtractor, java.util.function.BiFunction<ANRMatchDTO, ANRPageMatchDTO, String> pageExtractor) {
            this.name = name;
            this.needsEscape = needsEscape;
            this.itemExtractor = itemExtractor;
            this.pageExtractor = pageExtractor;
        }

        public String getName() {
            return name;
        }

        public boolean needsEscape() {
            return needsEscape;
        }

        public Function<ANRMatchDTO, String> getItemExtractor() {
            return itemExtractor;
        }

        public java.util.function.BiFunction<ANRMatchDTO, ANRPageMatchDTO, String> getPageExtractor() {
            return pageExtractor;
        }

        public static CsvField fromName(String name) {
            for (CsvField field : values()) {
                if (field.name.equalsIgnoreCase(name)) {
                    return field;
                }
            }
            throw new IllegalArgumentException("Unknown CSV field: " + name);
        }
    }

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss");

    private static final String QUOTE = "\"";
    private static final String COMMA = ",";
    private static final String NEWLINE = "\n";

    private final String outputDir;
    private final String csvHeader;
    private final List<CsvField> configuredFields;

    public CsvWriter(
            @Value("${app.anr.output-dir:'/output'}") String outputDir,
            @Value("${app.anr.writers.header:file_path,file_name,page_number,match_value,context_before,context_after,pages_analyzed,total_pages,processing_time,error_message,nnt,doi,defense_date}")
            String csvHeader) {
        this.outputDir = outputDir;
        this.csvHeader = csvHeader;
        this.configuredFields = parseHeader(csvHeader);
    }

    /**
     * Parse le header CSV configuré pour extraire les champs à inclure
     */
    private List<CsvField> parseHeader(String header) {
        if (header == null || header.isBlank()) {
            return List.of();
        }
        return Arrays.stream(header.split(","))
                .map(String::trim)
                .map(CsvField::fromName)
                .collect(Collectors.toList());
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
        String errorMessage = item.errorMessage();

        if (item.pageMatches().isEmpty() && (errorMessage == null || errorMessage.isEmpty())) {
            String line = buildCsvLine(item, null, false);
            writer.write(line);
        } else {
            for (ANRPageMatchDTO pageMatch : item.pageMatches()) {
                String line = buildCsvLine(item, pageMatch, false);
                writer.write(line);
            }
        }

        if (errorMessage != null && !errorMessage.isEmpty()) {
            String line = buildCsvLine(item, null, true);
            writer.write(line);
        }
    }

    /**
     * Construit une ligne CSV dynamiquement en fonction des champs configurés
     */
    private String buildCsvLine(ANRMatchDTO item, ANRPageMatchDTO pageMatch, boolean isErrorLine) {
        StringBuilder line = new StringBuilder();

        for (int i = 0; i < configuredFields.size(); i++) {
            CsvField field = configuredFields.get(i);
            String value;

            if (pageMatch != null) {
                // Cas avec pageMatch
                value = field.getPageExtractor().apply(item, pageMatch);
            } else if (isErrorLine) {
                // Cas ligne d'erreur
                if ("error_message".equals(field.getName())) {
                    value = item.errorMessage() != null ? item.errorMessage() : "";
                } else {
                    value = field.getItemExtractor().apply(item);
                }
            } else {
                // Cas sans pageMatch et sans erreur
                value = field.getItemExtractor().apply(item);
            }

            // Échapper la valeur si nécessaire
            if (field.needsEscape() && value != null && !value.isEmpty()) {
                value = value.replace(QUOTE, QUOTE + QUOTE)
                        .replace("\n", " ")
                        .replace("\r", " ");
                value = QUOTE + value + QUOTE;
            }

            // Ajouter la valeur (vide si null)
            if (value == null) {
                value = "";
            }

            line.append(value);

            // Ajouter une virgule sauf pour le dernier champ
            if (i < configuredFields.size() - 1) {
                line.append(COMMA);
            }
        }

        line.append(NEWLINE);
        return line.toString();
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

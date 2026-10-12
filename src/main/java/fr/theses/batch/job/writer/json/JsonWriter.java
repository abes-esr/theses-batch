package fr.theses.batch.job.writer.json;

import fr.theses.batch.business.anr.model.dto.ANRMatchDTO;
import fr.theses.batch.business.anr.model.dto.ANRPageMatchDTO;
import fr.theses.batch.job.writer.ANRFormatWriter;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Writer pour l'export JSON des résultats ANR
 */
@Component
public class JsonWriter implements ANRFormatWriter {

    /**
     * Enum définissant les champs possibles et leurs extracteurs de valeurs
     */
    private enum JsonField {
        FILE_PATH("file_path", true, item -> item.filePath(), (item, page) -> item.filePath()),
        FILE_NAME("file_name", true, item -> item.fileName(), (item, page) -> item.fileName()),
        PAGE_NUMBER("page_number", false, item -> 0, (item, page) -> page.pageNumber()),
        MATCH_VALUE("match_value", true, item -> "", (item, page) -> page.matchValue()),
        CONTEXT_BEFORE("context_before", true, item -> "", (item, page) -> page.contextBefore()),
        CONTEXT_AFTER("context_after", true, item -> "", (item, page) -> page.contextAfter()),
        PAGES_ANALYZED("pages_analyzed", false, item -> item.pagesAnalyzed(), (item, page) -> item.pagesAnalyzed()),
        TOTAL_PAGES("total_pages", false, item -> item.totalPages(), (item, page) -> item.totalPages()),
        PROCESSING_TIME("processing_time", false, item -> item.processingTime(), (item, page) -> item.processingTime()),
        ERROR_MESSAGE("error_message", true, item -> item.errorMessage() != null ? item.errorMessage() : "", (item, page) -> ""),
        NNT("nnt", true, item -> item.nnt() != null ? item.nnt() : "", (item, page) -> item.nnt() != null ? item.nnt() : ""),
        DOI("doi", true, item -> item.doi() != null ? item.doi() : "", (item, page) -> item.doi() != null ? item.doi() : ""),
        DEFENSE_DATE("defense_date", true, item -> item.defenseDate() != null ? String.valueOf(item.defenseDate()) : "", (item, page) -> item.defenseDate() != null ? String.valueOf(item.defenseDate()) : "");

        private final String name;
        private final boolean isString;
        private final Function<ANRMatchDTO, Object> itemExtractor;
        private final java.util.function.BiFunction<ANRMatchDTO, ANRPageMatchDTO, Object> pageExtractor;

        JsonField(String name, boolean isString, Function<ANRMatchDTO, Object> itemExtractor, java.util.function.BiFunction<ANRMatchDTO, ANRPageMatchDTO, Object> pageExtractor) {
            this.name = name;
            this.isString = isString;
            this.itemExtractor = itemExtractor;
            this.pageExtractor = pageExtractor;
        }

        public String getName() {
            return name;
        }

        public boolean isString() {
            return isString;
        }

        public Function<ANRMatchDTO, Object> getItemExtractor() {
            return itemExtractor;
        }

        public java.util.function.BiFunction<ANRMatchDTO, ANRPageMatchDTO, Object> getPageExtractor() {
            return pageExtractor;
        }

        public static JsonField fromName(String name) {
            for (JsonField field : values()) {
                if (field.name.equalsIgnoreCase(name)) {
                    return field;
                }
            }
            throw new IllegalArgumentException("Unknown JSON field: " + name);
        }
    }

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss");

    private final String outputDir;
    private final String jsonHeader;
    private final ObjectMapper objectMapper;
    private final boolean prettyPrint;
    private final List<JsonField> configuredFields;

    public JsonWriter(
            @Value("${app.anr.output-dir:'/output'}") String outputDir,
            @Value("${app.anr.json.pretty-print:true}") boolean prettyPrint,
            @Value("${app.anr.writers.header:file_path,file_name,page_number,match_value,context_before,context_after,pages_analyzed,total_pages,processing_time,error_message,nnt,doi,defense_date}") String jsonHeader) {
        this.outputDir = outputDir;
        this.prettyPrint = prettyPrint;
        this.jsonHeader = jsonHeader;
        this.configuredFields = parseHeader(jsonHeader);

        this.objectMapper = new ObjectMapper();
    }

    /**
     * Parse le header JSON configuré pour extraire les champs à inclure
     */
    private List<JsonField> parseHeader(String header) {
        if (header == null || header.isBlank()) {
            return List.of();
        }
        return Arrays.stream(header.split(","))
                .map(String::trim)
                .map(JsonField::fromName)
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
            Path jsonFilePath = Path.of(outputDir, timestamp + "_anr_results_" + year + ".json");

            if (!Files.exists(jsonFilePath.getParent())) {
                Files.createDirectories(jsonFilePath.getParent());
            }

            List<Map<String, Object>> results = yearItems.stream()
                    .flatMap(item -> toJsonResults(item).stream())
                    .collect(Collectors.toList());

            if (prettyPrint) {
                objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValue(jsonFilePath.toFile(), results);
            } else {
                objectMapper.writeValue(jsonFilePath.toFile(), results);
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

    private List<Map<String, Object>> toJsonResults(ANRMatchDTO item) {
        String errorMessage = item.errorMessage();
        List<Map<String, Object>> results = new ArrayList<>();

        if (item.pageMatches().isEmpty() && (errorMessage == null || errorMessage.isEmpty())) {
            Map<String, Object> result = buildJsonMap(item, null, false);
            results.add(result);
        } else {
            for (ANRPageMatchDTO pageMatch : item.pageMatches()) {
                Map<String, Object> result = buildJsonMap(item, pageMatch, false);
                results.add(result);
            }
        }

        if (errorMessage != null && !errorMessage.isEmpty()) {
            Map<String, Object> result = buildJsonMap(item, null, true);
            results.add(result);
        }

        return results;
    }

    /**
     * Construit une Map JSON dynamiquement en fonction des champs configurés
     */
    private Map<String, Object> buildJsonMap(ANRMatchDTO item, ANRPageMatchDTO pageMatch, boolean isErrorLine) {
        Map<String, Object> map = new LinkedHashMap<>();

        for (JsonField field : configuredFields) {
            Object value;

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

            // Ajouter la valeur à la map (null sera converti en null dans JSON)
            map.put(field.getName(), value);
        }

        return map;
    }
}

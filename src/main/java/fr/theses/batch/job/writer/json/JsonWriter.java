package fr.theses.batch.job.writer.json;

import fr.theses.batch.business.anr.model.dto.ANRMatchDTO;
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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Writer pour l'export JSON des résultats ANR
 */
@Component
public class JsonWriter implements ANRFormatWriter {

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss");

    private final String outputDir;
    private final ObjectMapper objectMapper;
    private final boolean prettyPrint;

    public JsonWriter(
            @Value("${app.anr.output-dir:'/output'}") String outputDir,
            @Value("${app.anr.json.pretty-print:true}") boolean prettyPrint) {
        this.outputDir = outputDir;
        this.prettyPrint = prettyPrint;

        this.objectMapper = new ObjectMapper();
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

            List<JsonAnrResult> results = yearItems.stream()
                    .map(this::toJsonResult)
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

    private JsonAnrResult toJsonResult(ANRMatchDTO item) {
        List<JsonPageMatch> pageMatches = item.pageMatches().stream()
                .map(pageMatch -> new JsonPageMatch(
                        pageMatch.pageNumber(),
                        pageMatch.matchValue(),
                        pageMatch.contextBefore(),
                        pageMatch.contextAfter()
                ))
                .collect(Collectors.toList());

        return new JsonAnrResult(
                item.filePath(),
                item.fileName(),
                item.pagesAnalyzed(),
                item.totalPages(),
                item.processingTime(),
                item.errorMessage(),
                pageMatches,
                item.matches(),
                item.nnt(),
                item.doi(),
                String.valueOf(item.defenseDate())
        );
    }

    private record JsonAnrResult(
            String filePath,
            String fileName,
            int pagesAnalyzed,
            int totalPages,
            double processingTime,
            String errorMessage,
            List<JsonPageMatch> pageMatches,
            List<String> matches,
            String nnt,
            String doi,
            String defenseDate
    ) {}

    private record JsonPageMatch(
            int pageNumber,
            String matchValue,
            String contextBefore,
            String contextAfter
    ) {}
}

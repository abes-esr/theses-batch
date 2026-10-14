package fr.theses.batch.job.writer.json;

import fr.theses.batch.business.anr.model.dto.ANRMatchDTO;
import fr.theses.batch.job.writer.ANRFormatWriter;
import fr.theses.batch.job.writer.ANRResultRows;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Writer pour l'export JSON des résultats ANR
 * Les champs et les lignes viennent d'ANRResultRows ; ce writer ne fait que les sérialiser en JSON
 */
@Component
public class JsonWriter implements ANRFormatWriter {

    private final ANRResultRows resultRows;
    private final ObjectMapper objectMapper;
    private final boolean prettyPrint;

    /**
     * Résultats cumulés depuis le début du run, par année de soutenance.
     * Un tableau JSON ne se complète pas en ajout : chaque fichier est réécrit en entier
     * à chaque chunk, pour rester valide même si le run s'interrompt.
     */
    private final Map<String, List<Map<String, Object>>> resultsByYear = new HashMap<>();

    public JsonWriter(
            ANRResultRows resultRows,
            @Value("${app.anr.json.pretty-print:true}") boolean prettyPrint) {
        this.resultRows = resultRows;
        this.prettyPrint = prettyPrint;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void write(Chunk<? extends ANRMatchDTO> chunk) throws Exception {
        write(chunk.getItems());
    }

    @Override
    public void write(List<? extends ANRMatchDTO> items) throws IOException {
        for (Map.Entry<String, List<ANRMatchDTO>> entry : resultRows.groupByYear(items).entrySet()) {
            String year = entry.getKey();
            // Fichier commun à tout le run, réécrit avec l'ensemble des résultats cumulés
            Path jsonFilePath = resultRows.resultFile(year, "json");

            List<Map<String, Object>> results = resultsByYear.computeIfAbsent(year, y -> new ArrayList<>());
            for (ANRMatchDTO item : entry.getValue()) {
                results.addAll(resultRows.toRows(item));
            }

            if (prettyPrint) {
                objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValue(jsonFilePath.toFile(), results);
            } else {
                objectMapper.writeValue(jsonFilePath.toFile(), results);
            }
        }
    }
}

package fr.theses.batch.job.writer.csv;

import fr.theses.batch.business.anr.model.dto.ANRMatchDTO;
import fr.theses.batch.job.writer.ANRFormatWriter;
import fr.theses.batch.job.writer.ANRResultRows;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Writer pour l'export CSV des résultats ANR
 * Les colonnes et les lignes viennent d'ANRResultRows ; ce writer ne fait que les sérialiser en CSV
 */
@Component
public class CsvWriter implements ANRFormatWriter {

    private static final String QUOTE = "\"";
    private static final String SEPARATOR = ",";
    private static final String NEWLINE = "\n";

    private final ANRResultRows resultRows;

    public CsvWriter(ANRResultRows resultRows) {
        this.resultRows = resultRows;
    }

    @Override
    public void write(Chunk<? extends ANRMatchDTO> chunk) throws Exception {
        write(chunk.getItems());
    }

    @Override
    public void write(List<? extends ANRMatchDTO> items) throws IOException {
        for (Map.Entry<String, List<ANRMatchDTO>> entry : resultRows.groupByYear(items).entrySet()) {
            // Fichier commun à tout le run : les chunks suivants y sont ajoutés
            Path csvFilePath = resultRows.resultFile(entry.getKey(), "csv");

            // UTF-8 explicite : ne pas dépendre du charset par défaut de la JVM qui exécute le batch
            try (BufferedWriter writer = Files.newBufferedWriter(csvFilePath, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                if (Files.size(csvFilePath) == 0) {
                    writer.write(String.join(SEPARATOR, resultRows.fieldNames()) + NEWLINE);
                }

                for (ANRMatchDTO item : entry.getValue()) {
                    for (Map<String, Object> row : resultRows.toRows(item)) {
                        writer.write(toCsvLine(row));
                    }
                }
            }
        }
    }

    private String toCsvLine(Map<String, Object> row) {
        return row.values().stream()
                .map(this::formatValue)
                .collect(Collectors.joining(SEPARATOR, "", NEWLINE));
    }

    /**
     * Formate une valeur pour le CSV : null en cellule vide, décimaux au format US (point décimal),
     * texte non vide entre guillemets, guillemets doublés et retours à la ligne neutralisés
     */
    private String formatValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Double number) {
            return String.format(Locale.US, "%.3f", number);
        }
        if (value instanceof String text) {
            if (text.isEmpty()) {
                return "";
            }
            String escaped = text.replace(QUOTE, QUOTE + QUOTE)
                    .replace("\n", " ")
                    .replace("\r", " ");
            return QUOTE + escaped + QUOTE;
        }
        return String.valueOf(value);
    }
}

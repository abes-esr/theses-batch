package fr.theses.batch.job.writer.csv;

import fr.theses.batch.business.anr.model.dto.ANRMatchDTO;
import fr.theses.batch.job.writer.ANRResultRows;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Writer pour l'export CSV des résultats ANR
 * Les colonnes et les lignes viennent d'ANRResultRows ; ce writer ne fait que les sérialiser en CSV
 */
@Component
public class ANRCsvWriter implements ItemWriter<ANRMatchDTO> {

    private static final String QUOTE = "\"";
    private static final String SEPARATOR = ",";
    private static final String NEWLINE = "\n";

    private static final OpenOption[] CREATE = {
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE};
    private static final OpenOption[] APPEND = {StandardOpenOption.APPEND};

    private final ANRResultRows resultRows;

    /**
     * Années dont le fichier a déjà été créé, en-tête compris, pendant ce run : les chunks suivants y ajoutent
     * leurs lignes. Évite d'interroger la taille du fichier à chaque chunk pour savoir s'il faut l'en-tête.
     */
    private final Set<String> startedYears = new HashSet<>();

    public ANRCsvWriter(ANRResultRows resultRows) {
        this.resultRows = resultRows;
    }

    /**
     * Fichiers d'erreurs déjà créés pour éviter de réécrire l'en-tête
     */
    private boolean errorFileStarted = false;

    @Override
    public void write(Chunk<? extends ANRMatchDTO> chunk) throws IOException {
        // Séparer les lignes normales des lignes d'erreur pour tous les items
        List<Map<String, Object>> allNormalRows = new ArrayList<>();
        List<Map<String, Object>> allErrorRows = new ArrayList<>();
        
        for (ANRMatchDTO item : chunk.getItems()) {
            ANRResultRows.Pair<List<Map<String, Object>>, List<Map<String, Object>>> separated = 
                    resultRows.toRowsSeparated(item);
            allNormalRows.addAll(separated.first);
            allErrorRows.addAll(separated.second);
        }

        // Écrire les erreurs dans un fichier dédié (toutes années confondues)
        if (!allErrorRows.isEmpty()) {
            writeErrorRows(allErrorRows);
        }

        // Écrire les lignes normales dans les fichiers par année
        // On doit regrouper par année, mais on n'a plus l'item... 
        // Il faut associer chaque ligne à son année
        // On va donc reconstruire les items normaux et les écrire par année
        List<ANRMatchDTO> normalItems = (List<ANRMatchDTO>) chunk.getItems().stream()
                .filter(item -> item.errorMessage() == null || item.errorMessage().isEmpty())
                .toList();
        
        for (Map.Entry<String, List<ANRMatchDTO>> entry : resultRows.groupByYear(normalItems).entrySet()) {
            Path csvFilePath = resultRows.resultFile(entry.getKey(), "csv");
            boolean firstWrite = startedYears.add(entry.getKey());

            try (BufferedWriter writer = Files.newBufferedWriter(csvFilePath, StandardCharsets.UTF_8,
                    firstWrite ? CREATE : APPEND)) {
                if (firstWrite) {
                    writer.write(String.join(SEPARATOR, resultRows.fieldNames()) + NEWLINE);
                }

                for (ANRMatchDTO item : entry.getValue()) {
                    // Écrire seulement les lignes normales (pas les erreurs)
                    ANRResultRows.Pair<List<Map<String, Object>>, List<Map<String, Object>>> separated = 
                            resultRows.toRowsSeparated(item);
                    for (Map<String, Object> row : separated.first) {
                        writer.write(toCsvLine(row));
                    }
                }
            }
        }
    }

    /**
     * Écrit directement une liste de lignes d'erreur dans un fichier dédié
     */
    private void writeErrorRows(List<Map<String, Object>> errorRows) throws IOException {
        Path errorFilePath = resultRows.errorFile("csv");
        boolean firstWrite = !errorFileStarted;

        try (BufferedWriter writer = Files.newBufferedWriter(errorFilePath, StandardCharsets.UTF_8,
                firstWrite ? CREATE : APPEND)) {
            if (firstWrite) {
                writer.write(String.join(SEPARATOR, resultRows.fieldNames()) + NEWLINE);
                errorFileStarted = true;
            }

            for (Map<String, Object> row : errorRows) {
                writer.write(toCsvLine(row));
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
            return formatDecimal(number);
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

    /**
     * Trois décimales, point décimal, arrondi au plus proche (moitié vers le haut) : même résultat que
     * {@code String.format(Locale.US, "%.3f", ...)}, sans analyser un motif de format à chaque valeur.
     * NaN et infinis, sans écriture décimale, passent par String.format.
     */
    static String formatDecimal(double number) {
        if (!Double.isFinite(number)) {
            return String.format(Locale.US, "%.3f", number);
        }
        String formatted = BigDecimal.valueOf(number).setScale(3, RoundingMode.HALF_UP).toPlainString();
        // BigDecimal n'a pas de zéro négatif ; String.format garde le signe (« -0.000 »)
        boolean negative = number < 0 || Double.compare(number, -0.0) == 0;
        return negative && formatted.equals("0.000") ? "-0.000" : formatted;
    }
}

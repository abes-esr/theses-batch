package fr.theses.batch.job.writer;

import fr.theses.batch.business.anr.model.dto.ANRMatchDTO;
import fr.theses.batch.business.anr.model.dto.ANRPageMatchDTO;
import fr.theses.batch.job.RunTimestamp;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Mise en lignes des résultats ANR, commune à tous les formats de sortie
 * Décide des colonnes (app.anr.writers.header), des lignes produites pour chaque fichier traité
 * et du fichier de destination ; chaque writer ne fait que sérialiser ces lignes dans son format
 */
@Component
public class ANRResultRows {

    private final String outputDir;
    private final String runTimestamp;
    private final List<ANRResultField> fields;

    public ANRResultRows(
            RunTimestamp runTimestamp,
            @Value("${app.anr.output-dir:output}") String outputDir,
            @Value("${app.anr.writers.header:file_path,file_name,page_number,match_value,preceding_context,following_context,analyzed_pages_number,total_pages,processing_time,error_message,nnt,doi,defense_date}")
            String header) {
        this.runTimestamp = runTimestamp.value();
        this.outputDir = outputDir;
        this.fields = parseHeader(header);
    }

    /**
     * Parse le header configuré pour extraire les champs à inclure, dans l'ordre
     */
    private List<ANRResultField> parseHeader(String header) {
        if (header == null || header.isBlank()) {
            return List.of();
        }
        return Arrays.stream(header.split(","))
                .map(String::trim)
                .map(ANRResultField::fromName)
                .toList();
    }

    /**
     * @return les noms des champs configurés, dans l'ordre des colonnes
     */
    public List<String> fieldNames() {
        return fields.stream().map(ANRResultField::fieldName).toList();
    }

    /**
     * Groupe les items par année de soutenance, un fichier de résultats étant produit par année
     */
    public Map<String, List<ANRMatchDTO>> groupByYear(List<? extends ANRMatchDTO> items) {
        return items.stream().collect(Collectors.groupingBy(this::extractYear));
    }

    /**
     * Fichier de résultats d'une année pour le run en cours, son répertoire étant créé au besoin
     * Un seul fichier par année et par format pour tout le run : les chunks successifs y écrivent tous
     *
     * @param year      l'année de soutenance, ou "unknown"
     * @param extension l'extension du format, sans point
     */
    public Path resultFile(String year, String extension) throws IOException {
        Path path = Path.of(outputDir, runTimestamp + "_anr_results_" + year + "." + extension);
        Files.createDirectories(path.getParent());
        return path;
    }

    /**
     * Lignes de résultat d'un fichier traité, chacune associant nom de champ et valeur, dans l'ordre des colonnes :
     * une ligne par correspondance, une ligne vide s'il n'y en a aucune et pas d'erreur,
     * plus une ligne portant le message d'erreur si le traitement a échoué
     */
    public List<Map<String, Object>> toRows(ANRMatchDTO item) {
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean hasError = item.errorMessage() != null && !item.errorMessage().isEmpty();

        for (ANRPageMatchDTO pageMatch : item.pageMatches()) {
            rows.add(toRow(item, pageMatch));
        }
        if (item.pageMatches().isEmpty() && !hasError) {
            rows.add(toRow(item, null));
        }
        if (hasError) {
            rows.add(toRow(item, null));
        }
        return rows;
    }

    private Map<String, Object> toRow(ANRMatchDTO item, ANRPageMatchDTO pageMatch) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (ANRResultField field : fields) {
            row.put(field.fieldName(), field.extract(item, pageMatch));
        }
        return row;
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
}

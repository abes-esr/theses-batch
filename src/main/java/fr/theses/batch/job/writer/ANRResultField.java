package fr.theses.batch.job.writer;

import fr.theses.batch.business.anr.model.dto.ANRMatchDTO;
import fr.theses.batch.business.anr.model.dto.ANRPageMatchDTO;

import java.util.function.BiFunction;

/**
 * Champs disponibles dans les fichiers de résultats ANR, communs à tous les formats
 * Le nom d'un champ sert à la fois dans app.anr.writers.header, comme en-tête de colonne CSV et comme clé JSON
 */
public enum ANRResultField {
    FILE_PATH("file_path", (item, page) -> item.filePath()),
    FILE_NAME("file_name", (item, page) -> item.fileName()),
    PAGE_NUMBER("page_number", (item, page) -> page != null ? page.pageNumber() : null),
    MATCH_VALUE("match_value", (item, page) -> page != null ? page.matchValue() : ""),
    PRECEDING_CONTEXT("preceding_context", (item, page) -> page != null ? page.contextBefore() : ""),
    FOLLOWING_CONTEXT("following_context", (item, page) -> page != null ? page.contextAfter() : ""),
    ANALYZED_PAGES_NUMBER("analyzed_pages_number", (item, page) -> item.pagesAnalyzed()),
    TOTAL_PAGES("total_pages", (item, page) -> item.totalPages()),
    PROCESSING_TIME("processing_time", (item, page) -> item.processingTime()),
    // Le message d'erreur ne figure que sur la ligne d'erreur, pas sur les lignes de correspondance
    ERROR_MESSAGE("error_message", (item, page) -> page != null ? "" : orEmpty(item.errorMessage())),
    NNT("nnt", (item, page) -> orEmpty(item.nnt())),
    DOI("doi", (item, page) -> orEmpty(item.doi())),
    DEFENSE_DATE("defense_date", (item, page) -> item.defenseDate() != null ? String.valueOf(item.defenseDate()) : "");

    private final String fieldName;
    private final BiFunction<ANRMatchDTO, ANRPageMatchDTO, Object> extractor;

    ANRResultField(String fieldName, BiFunction<ANRMatchDTO, ANRPageMatchDTO, Object> extractor) {
        this.fieldName = fieldName;
        this.extractor = extractor;
    }

    public String fieldName() {
        return fieldName;
    }

    /**
     * Extrait la valeur du champ pour une ligne de résultat
     *
     * @param item le résultat du fichier traité
     * @param page la correspondance de la ligne, null pour une ligne sans correspondance ou une ligne d'erreur
     * @return la valeur typée (String, Integer, Double), ou null si le champ ne s'applique pas à la ligne
     */
    public Object extract(ANRMatchDTO item, ANRPageMatchDTO page) {
        return extractor.apply(item, page);
    }

    /**
     * @param fieldName nom du champ tel qu'écrit dans app.anr.writers.header (casse ignorée)
     * @return le champ correspondant
     * @throws IllegalArgumentException si le nom ne correspond à aucun champ
     */
    public static ANRResultField fromName(String fieldName) {
        for (ANRResultField field : values()) {
            if (field.fieldName.equalsIgnoreCase(fieldName)) {
                return field;
            }
        }
        throw new IllegalArgumentException("Unknown result field: " + fieldName);
    }

    private static String orEmpty(String value) {
        return value != null ? value : "";
    }
}

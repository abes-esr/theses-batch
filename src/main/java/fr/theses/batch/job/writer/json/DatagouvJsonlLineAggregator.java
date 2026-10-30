package fr.theses.batch.job.writer.json;

import fr.theses.batch.business.datagouv.model.ExportThesis;
import org.springframework.batch.infrastructure.item.file.transform.LineAggregator;
import org.jspecify.annotations.NonNull;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Agrégateur de ligne JSONL (JSON Lines) transformant un objet ExportThesis 
 * en sa représentation JSON compacte sur une seule ligne.
 */
public class DatagouvJsonlLineAggregator implements LineAggregator<ExportThesis> {

    private final ObjectMapper objectMapper;

    /**
     * Constructeur injectant le mapper JSON Jackson.
     *
     * @param objectMapper L'instance ObjectMapper de Spring.
     */
    public DatagouvJsonlLineAggregator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Sérialise la thèse en chaîne de caractères JSON.
     *
     * @param item L'objet ExportThesis.
     * @return La chaîne JSON correspondante.
     * @throws IllegalStateException si la sérialisation échoue.
     */
    @Override
    @NonNull
    public String aggregate(@NonNull ExportThesis item) {
        try {
            String json = objectMapper.writeValueAsString(item);
            if (json == null) {
                throw new IllegalStateException("La sérialisation JSON de la thèse a retourné une valeur nulle.");
            }
            return json;
        } catch (JacksonException e) {
            throw new IllegalStateException("Erreur de sérialisation JSON de la thèse", e);
        }
    }
}

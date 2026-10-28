package fr.theses.batch.business.datagouv.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * DTO représentant les résumés par langue dans le format d'export historique.
 *
 * @param fr    Résumé en français.
 * @param en    Résumé en anglais.
 * @param autre Liste des résumés dans d'autres langues.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExportResumes(
        String fr,
        String en,
        List<String> autre
) {}

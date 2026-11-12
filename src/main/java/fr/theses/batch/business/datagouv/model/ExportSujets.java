package fr.theses.batch.business.datagouv.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * DTO représentant les sujets par langue dans le format d'export historique.
 *
 * @param fr    Sujets en français.
 * @param en    Sujets en anglais.
 * @param autre Sujets dans d'autres langues.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExportSujets(
        List<String> fr,
        List<String> en,
        List<String> autre
) {}

package fr.theses.batch.business.datagouv.model;

/**
 * Mot-clé d'une thèse dans le champ mapSujets de l'API theses.fr.
 *
 * @param keyword Libellé du mot-clé.
 * @param type    "sujet" pour un mot-clé libre, "sujetsRameau" pour un sujet RAMEAU.
 */
public record SubjectKeyword(
        String keyword,
        String type
) {
    public static final String TYPE_RAMEAU = "sujetsRameau";
}

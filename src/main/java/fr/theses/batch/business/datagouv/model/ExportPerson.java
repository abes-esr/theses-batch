package fr.theses.batch.business.datagouv.model;

/**
 * DTO représentant une personne dans le format d'export historique (CSV, JSON, JSONL).
 *
 * @param nom    Nom de la personne.
 * @param prenom Prénom de la personne.
 * @param idref  Identifiant IdRef / PPN de la personne.
 */
public record ExportPerson(
        String nom,
        String prenom,
        String idref
) {}

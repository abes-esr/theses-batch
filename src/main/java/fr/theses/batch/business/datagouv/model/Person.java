package fr.theses.batch.business.datagouv.model;

/**
 * Représente une personne (auteur, directeur, président, examinateur, rapporteur) dans l'API theses.fr.
 *
 * @param nom    Nom de famille.
 * @param prenom Prénom.
 * @param ppn    Identifiant PPN (IdRef) de la personne.
 */
public record Person(
        String nom,
        String prenom,
        String ppn
) {}

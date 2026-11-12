package fr.theses.batch.business.datagouv.model;

/**
 * Représente un établissement ou une organisation (soutenance, cotutelle, école doctorale, partenaire de recherche)
 * dans l'API theses.fr.
 *
 * @param nom  Nom de l'établissement ou de l'organisation.
 * @param ppn  Identifiant PPN (IdRef) de l'établissement ou de l'organisation.
 * @param type Type d'organisme (renseigné pour les partenaires de recherche).
 */
public record Organization(
        String nom,
        String ppn,
        String type
) {}

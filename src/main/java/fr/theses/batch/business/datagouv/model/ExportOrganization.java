package fr.theses.batch.business.datagouv.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * DTO représentant une organisation dans le format d'export historique.
 *
 * @param nom   Nom de l'organisation.
 * @param idref Identifiant IdRef / PPN de l'organisation.
 * @param type  Type de partenaire (optionnel).
 */
public record ExportOrganization(
        String nom,
        String idref,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String type
) {}

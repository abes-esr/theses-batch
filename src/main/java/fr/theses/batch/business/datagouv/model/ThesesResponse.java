package fr.theses.batch.business.datagouv.model;

import java.util.List;

/**
 * Représente la structure globale de la réponse JSON renvoyée par l'API theses.fr.
 *
 * @param totalHits Nombre total de résultats correspondant à la recherche.
 * @param theses    Liste des thèses retournées pour la page courante.
 */
public record ThesesResponse(
        int totalHits,
        List<Thesis> theses
) {}

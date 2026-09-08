package fr.theses.batch.business.anr.model.dto;

import lombok.With;

import java.util.ArrayList;
import java.util.List;

/**
 * DTO pour les résultats de recherche ANR dans les PDF
 */
@With
public record ANRMatchDTO(
    /**
     * Chemin du fichier PDF
     */
    String filePath,
    
    /**
     * Nom du fichier
     */
    String fileName,
    
    /**
     * Liste des correspondances ANR trouvées (pour compatibilité)
     */
    List<String> matches,
    
    /**
     * Liste des correspondances avec numéro de page et contexte
     */
    List<ANRPageMatchDTO> pageMatches,
    
    /**
     * Nombre de pages analysées
     */
    int pagesAnalyzed,
    
    /**
     * Nombre total de pages dans le PDF
     */
    int totalPages,
    
    /**
     * Durée du traitement en secondes
     */
    double processingTime,
    
    /**
     * Message d'erreur si applicable
     */
    String errorMessage
) {
    public ANRMatchDTO {
        matches = matches != null ? new ArrayList<>(matches) : new ArrayList<>();
        pageMatches = pageMatches != null ? new ArrayList<>(pageMatches) : new ArrayList<>();
    }
}

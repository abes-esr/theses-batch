package fr.theses.batch.business.anr.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * DTO pour les résultats de recherche ANR dans les PDF
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ANRMatchDTO {
    
    /**
     * Chemin du fichier PDF
     */
    private String filePath;
    
    /**
     * Nom du fichier
     */
    private String fileName;
    
    /**
     * Liste des correspondances ANR trouvées (pour compatibilité)
     */
    private List<String> matches = new ArrayList<>();
    
    /**
     * Liste des correspondances avec numéro de page et contexte
     */
    private List<ANRPageMatchDTO> pageMatches = new ArrayList<>();
    
    /**
     * Nombre de pages analysées
     */
    private int pagesAnalyzed;
    
    /**
     * Nombre total de pages dans le PDF
     */
    private int totalPages;
    
    /**
     * Durée du traitement en secondes
     */
    private double processingTime;
    
    /**
     * Message d'erreur si applicable
     */
    private String errorMessage;
}

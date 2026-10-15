package fr.theses.batch.business.anr.model.dto;

import lombok.With;

/**
 * DTO pour une correspondance ANR trouvée sur une page spécifique
 */
@With
public record ANRPageMatchDTO(
    /**
     * Numéro de la page où la correspondance a été trouvée
     */
    int pageNumber,
    
    /**
     * Valeur de la correspondance ANR
     */
    String matchValue,
    
    /**
     * Texte avant la correspondance (contexte)
     */
    String contextBefore,
    
    /**
     * Texte après la correspondance (contexte)
     */
    String contextAfter
) {}

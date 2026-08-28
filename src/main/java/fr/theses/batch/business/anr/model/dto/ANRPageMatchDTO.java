package fr.theses.batch.business.anr.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO pour une correspondance ANR trouvée sur une page spécifique
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ANRPageMatchDTO {
    
    /**
     * Numéro de la page où la correspondance a été trouvée
     */
    private int pageNumber;
    
    /**
     * Valeur de la correspondance ANR
     */
    private String matchValue;
    
    /**
     * Texte avant la correspondance (contexte)
     */
    private String contextBefore;
    
    /**
     * Texte après la correspondance (contexte)
     */
    private String contextAfter;
}

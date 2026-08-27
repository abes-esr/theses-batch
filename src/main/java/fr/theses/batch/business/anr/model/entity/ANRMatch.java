package fr.theses.batch.business.anr.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Entité JPA pour stocker les résultats de recherche ANR
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "anr_matches")
public class ANRMatch {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * Chemin du fichier PDF
     */
    @Column(name = "file_path", nullable = false, length = 1000)
    private String filePath;
    
    /**
     * Nom du fichier
     */
    @Column(name = "file_name", nullable = false, length = 500)
    private String fileName;
    
    /**
     * Correspondance ANR trouvée
     */
    @Column(name = "match_value", nullable = false, length = 100)
    private String matchValue;
    
    /**
     * Date de traitement
     */
    @Column(name = "processing_date", nullable = false)
    private LocalDateTime processingDate;
    
    /**
     * Numéro de la page où la correspondance a été trouvée
     */
    @Column(name = "page_number")
    private Integer pageNumber;
    
    /**
     * Identifiant du job Spring Batch
     */
    @Column(name = "job_id", length = 100)
    private String jobId;
    
    /**
     * Identifiant de l'exécution
     */
    @Column(name = "execution_id")
    private Long executionId;
}

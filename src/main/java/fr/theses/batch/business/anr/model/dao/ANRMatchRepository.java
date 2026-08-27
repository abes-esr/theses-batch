package fr.theses.batch.business.anr.model.dao;

import fr.theses.batch.business.anr.model.entity.ANRMatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Repository JPA pour les correspondances ANR
 */
@Repository
public interface ANRMatchRepository extends JpaRepository<ANRMatch, Long> {
    
    /**
     * Trouve les correspondances par nom de fichier
     * 
     * @param fileName Nom du fichier
     * @return Liste des correspondances
     */
    List<ANRMatch> findByFileName(String fileName);
    
    /**
     * Trouve les correspondances par chemin de fichier
     * 
     * @param filePath Chemin du fichier
     * @return Liste des correspondances
     */
    List<ANRMatch> findByFilePath(String filePath);
    
    /**
     * Trouve les correspondances par valeur ANR
     * 
     * @param matchValue Valeur ANR
     * @return Liste des correspondances
     */
    List<ANRMatch> findByMatchValue(String matchValue);
    
    /**
     * Trouve les correspondances par date de traitement
     * 
     * @param processingDate Date de traitement
     * @return Liste des correspondances
     */
    List<ANRMatch> findByProcessingDate(LocalDateTime processingDate);
    
    /**
     * Trouve les correspondances par identifiant de job
     * 
     * @param jobId Identifiant du job
     * @return Liste des correspondances
     */
    List<ANRMatch> findByJobId(String jobId);
    
    /**
     * Trouve les correspondances par identifiant d'exécution
     * 
     * @param executionId Identifiant de l'exécution
     * @return Liste des correspondances
     */
    List<ANRMatch> findByExecutionId(Long executionId);
}

package fr.theses.batch.job.processor;

import fr.theses.batch.business.anr.model.dto.ANRMatchDTO;
import fr.theses.batch.client.SolrClient;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * Processor pour l'enrichissement des données ANR via Solr.
 * Ce processor :
 * 1. Extrait l'identifiant STAR depuis le filePath (après "THESE_" et avant le "/" suivant)
 * 2. Appelle le serveur Solr via SolrClient pour récupérer les champs NNT, DOI et date de soutenance
 * 3. Enrichit l'ANRMatchDTO avec ces informations
 */
@Component("anrSolrEnricherProcessor")
@Slf4j
public class ANRSolrEnricherProcessor implements ItemProcessor<ANRMatchDTO, ANRMatchDTO> {

    private final SolrClient solrClient;
    
    @Value("${app.solr.id-field:id}")
    private String solrIdField;
    
    @Value("${app.solr.nnt-field:nnt}")
    private String solrNntField;
    
    @Value("${app.solr.doi-field:doi}")
    private String solrDoiField;
    
    @Value("${app.solr.defense-date-field:dateSoutenance_dt}")
    private String solrDefenseDateField;

    public ANRSolrEnricherProcessor(SolrClient solrClient) {
        this.solrClient = solrClient;
    }

    @PostConstruct
    public void init() {
        log.info("ANRSolrEnricherProcessor initialisé - Solr activé: {}, URL: {}", 
                solrClient.isEnabled(), solrClient.getBaseUrl());
    }

    @Override
    public ANRMatchDTO process(ANRMatchDTO item) throws Exception {
        Instant startTime = Instant.now();

        if (!solrClient.isEnabled()) {
            log.debug("Solr est désactivé, retour de l'item sans enrichissement");
            return null;
        }

        try {
            // 1. Extraire l'identifiant STAR depuis le filePath
            String idStar = extractIdStarFromFilePath(item.filePath());
            
            if (idStar == null || idStar.isBlank()) {
                log.warn("Impossible d'extraire l'identifiant STAR du fichier: {}", item.filePath());
                return null;
            }

            log.debug("Id STAR extrait: {} pour le fichier: {}", idStar, item.filePath());

            // 2. Construire la liste des champs à retourner
            String returnFields = String.join(",", solrNntField, solrDoiField, solrDefenseDateField);
            
            // 3. Appeler Solr pour récupérer les données
            Map<String, Object> solrDocument = solrClient.queryById(solrIdField, idStar, returnFields);
            
            if (solrDocument.isEmpty()) {
                log.debug("Aucun document trouvé dans Solr pour l'id STAR: {}", idStar);
                return null;
            }

            // 4. Extraire les champs enrichis
            String nnt = solrClient.extractFieldValue(solrDocument, solrNntField);
            String doi = solrClient.extractFieldValue(solrDocument, solrDoiField);
            String defenseDate = solrClient.extractFieldValue(solrDocument, solrDefenseDateField);

            log.debug("Enrichissement Solr terminé pour {} - id_star: {}, NNT: {}, DOI: {}, Date: {}",
                    item.fileName(), idStar, nnt, doi, defenseDate);

            // 5. Retourner l'item enrichi
            return item
                    .withNnt(nnt)
                    .withDoi(doi)
                    .withDefenseDate(defenseDate);

        } catch (Exception e) {
            log.error("Erreur lors de l'enrichissement Solr pour le fichier {}: {}", item.filePath(), e.getMessage(), e);
            return item
                    .withErrorMessage("Erreur Solr: " + e.getMessage());
        }
    }

    /**
     * Extrait l'identifiant STAR depuis le chemin du fichier.
     * L'identifiant est la chaîne de caractères après "THESE_" et avant le "/" suivant.
     * 
     * Exemple: 
     * - ".../THESE_123456/nom_fichier.pdf" → "123456"
     * - ".../THESE_ABC123/nom_fichier.pdf" → "ABC123"
     * 
     * @param filePath Chemin du fichier
     * @return Identifiant STAR, ou null si non trouvé
     */
    private String extractIdStarFromFilePath(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return null;
        }
        
        // Chercher "THESE_" dans le chemin
        int theseIndex = filePath.indexOf("THESE_");
        if (theseIndex == -1) {
            return null;
        }
        
        // Avancer après "THESE_"
        int startIndex = theseIndex + 6; // "THESE_" = 6 caractères
        
        // Chercher le prochain "/" après "THESE_"
        int slashIndex = filePath.indexOf('/', startIndex);
        
        // Si pas de "/", chercher "\" pour les chemins Windows
        if (slashIndex == -1) {
            slashIndex = filePath.indexOf('\\', startIndex);
        }
        
        // Si aucun séparateur trouvé, prendre jusqu'à la fin de la chaîne
        if (slashIndex == -1) {
            slashIndex = filePath.length();
        }
        
        // Extraire l'identifiant
        return filePath.substring(startIndex, slashIndex);
    }
}

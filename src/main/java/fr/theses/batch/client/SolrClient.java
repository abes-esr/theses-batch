package fr.theses.batch.client;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Client REST générique pour interagir avec un serveur Solr.
 * Ce client est conçu pour être réutilisable par tous les packages de l'application.
 */
@Component
@Slf4j
public class SolrClient {

    private final RestClient.Builder restClientBuilder;
    private RestClient restClient;
    @Value("${app.solr.enabled:false}")
    private boolean enabled;
    @Value("${app.solr.base-url:http://localhost:8983/solr1}")
    private String baseUrl;
    @Value("${app.solr.connect-timeout:5000}")
    private int connectTimeout;
    @Value("${app.solr.read-timeout:5000}")
    private int readTimeout;


    @PostConstruct
    public void init() {
        String fullBaseUrl = baseUrl;

        if (!fullBaseUrl.startsWith("http://") && !fullBaseUrl.startsWith("https://")) {
            log.warn("Solr base URL does not start with http:// or https://. Prepending http://. Base URL: {}", fullBaseUrl);
            fullBaseUrl = "http://" + fullBaseUrl;
        }

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);

        this.restClient = restClientBuilder
                .baseUrl(fullBaseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    public SolrClient(RestClient.Builder restClientBuilder) {
        this.restClientBuilder = restClientBuilder;
    }

    /**
     * Exécute une requête SELECT sur Solr et retourne la réponse sous forme de Map.
     * 
     * @param query La requête Solr (paramètre q)
     * @param fields Les champs à retourner (paramètre fl), séparés par des virgules
     * @param rows Nombre maximum de résultats (paramètre rows)
     * @return Map contenant la réponse JSON de Solr
     */
    public Map<String, Object> query(String query, String fields, int rows) {
        if (!enabled) {
            log.debug("Solr est désactivé, retour de réponse vide");
            return Map.of();
        }

        log.debug("Exécution requête Solr: query={}, fields={}, rows={}", query, fields, rows);

        try {
            return restClient.get()
                    .uri("/select?q={query}&fl={fields}&rows={rows}&wt=json", query, fields, rows)
                    .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN)
                    .retrieve()
                    .onStatus(status -> status != HttpStatus.OK, (request, response) -> {
                        log.error("Erreur Solr - Status: {}, Body: {}", response.getStatusCode(), response.getBody());
                    })
                    .body(Map.class);
        } catch (Exception e) {
            log.error("Erreur lors de la requête Solr: {}", e.getMessage(), e);
            return Map.of();
        }
    }

    /**
     * Exécute une requête SELECT sur Solr avec une simple requête.
     * 
     * @param query La requête Solr (paramètre q)
     * @return Map contenant la réponse JSON de Solr
     */
    public Map<String, Object> query(String query) {
        return query(query, "*", 1);
    }

    /**
     * Exécute une requête SELECT sur Solr pour un identifiant spécifique.
     * 
     * @param idField Nom du champ Solr pour la recherche
     * @param idValue Valeur de l'identifiant à rechercher
     * @param returnFields Champs à retourner, séparés par des virgules
     * @return Map contenant les données du premier document trouvé
     */
    public Map<String, Object> queryById(String idField, String idValue, String returnFields) {
        String query = idField + ":" + escapeSolrValue(idValue);
        Map<String, Object> response = query(query, returnFields, 1);
        
        if (response.isEmpty()) {
            return Map.of();
        }
        
        return extractFirstDocument(response);
    }

    /**
     * Extrait le premier document de la réponse Solr.
     * 
     * @param response Réponse complète de Solr
     * @return Map contenant les champs du premier document, ou map vide si aucun
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> extractFirstDocument(Map<String, Object> response) {
        if (!response.containsKey("response")) {
            log.warn("Réponse Solr invalide: pas de clé 'response'");
            return Map.of();
        }
        
        Object responseObj = response.get("response");
        if (!(responseObj instanceof Map)) {
            log.warn("Réponse Solr invalide: 'response' n'est pas une Map");
            return Map.of();
        }
        
        Map<String, Object> solrResponse = (Map<String, Object>) responseObj;

        if (!solrResponse.containsKey("docs")) {
            log.warn("Réponse Solr invalide: pas de clé 'docs'");
            return Map.of();
        }
        
        Object docsObj = solrResponse.get("docs");
        if (!(docsObj instanceof java.util.List)) {
            log.warn("Réponse Solr invalide: 'docs' n'est pas une List");
            return Map.of();
        }
        
        java.util.List<Object> docs = (java.util.List<Object>) docsObj;
        
        if (docs.isEmpty()) {
            log.debug("Aucun document trouvé dans la réponse Solr");
            return Map.of();
        }
        
        Object firstDoc = docs.get(0);
        if (!(firstDoc instanceof Map)) {
            log.warn("Réponse Solr invalide: premier document n'est pas une Map");
            return Map.of();
        }
        
        return (Map<String, Object>) firstDoc;
    }

    /**
     * Extrait une valeur de champ d'un document Solr.
     * 
     * @param document Document Solr
     * @param fieldName Nom du champ
     * @return Valeur du champ, ou null
     */
    @SuppressWarnings("unchecked")
    public String extractFieldValue(Map<String, Object> document, String fieldName) {
        if (document == null || fieldName == null || !document.containsKey(fieldName)) {
            return null;
        }
        
        Object fieldValue = document.get(fieldName);
        
        if (fieldValue == null) {
            return null;
        }
        
        if (fieldValue instanceof String) {
            return (String) fieldValue;
        }
        
        if (fieldValue instanceof java.util.List) {
            java.util.List<?> list = (java.util.List<?>) fieldValue;
            if (!list.isEmpty() && list.get(0) instanceof String) {
                return (String) list.get(0);
            }
        }
        
        return fieldValue.toString();
    }

    /**
     * Échappe une valeur pour une requête Solr.
     * 
     * @param value Valeur à échapper
     * @return Valeur échappée
     */
    private String escapeSolrValue(String value) {
        if (value == null) {
            return "";
        }
        // Échappement des caractères spéciaux Solr
        return value.replace("\\", "\\\\")
                     .replace("+", "\\+")
                     .replace("-", "\\-")
                     .replace("&", "\\&")
                     .replace("|", "\\|")
                     .replace("(", "\\(")
                     .replace(")", "\\)")
                     .replace("[", "\\[")
                     .replace("]", "\\]")
                     .replace("{", "\\{")
                     .replace("}", "\\}")
                     .replace("^", "\\^")
                     .replace("~", "\\~")
                     .replace(":", "\\:")
                     .replace("\"", "\\\"")
                     .replace("/", "\\/")
                     .replace(" ", "\\ ");
    }

    /**
     * Vérifie si Solr est activé.
     * 
     * @return true si Solr est activé
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Retourne l'URL de base de Solr.
     * 
     * @return URL de base
     */
    public String getBaseUrl() {
        String fullBaseUrl = baseUrl;
        if (!fullBaseUrl.startsWith("http://") && !fullBaseUrl.startsWith("https://")) {
            fullBaseUrl = "http://" + fullBaseUrl;
        }
        return fullBaseUrl;
    }
}

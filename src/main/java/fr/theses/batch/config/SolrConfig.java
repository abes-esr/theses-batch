package fr.theses.batch.config;

import fr.theses.batch.client.SolrClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

import java.util.Arrays;
import java.util.List;

/**
 * Configuration pour le client Solr et RestClient.
 */
@Configuration
public class SolrConfig {

    /**
     * Crée un RestClient.Builder pour l'injection dans SolrClient.
     * Configure les message converters pour gérer text/plain comme JSON.
     * 
     * @return RestClient.Builder configuré
     */
    @Bean
    public RestClient.Builder restClientBuilder() {
        // Configurer le MappingJackson2HttpMessageConverter pour accepter text/plain
        MappingJackson2HttpMessageConverter jsonConverter = new MappingJackson2HttpMessageConverter();
        jsonConverter.setSupportedMediaTypes(
            Arrays.asList(
                MediaType.APPLICATION_JSON,
                new MediaType("text", "plain", java.nio.charset.StandardCharsets.UTF_8)
            )
        );
        
        return RestClient.builder()
                .messageConverters(List.of(jsonConverter));
    }

    /**
     * Crée le client Solr avec injection des paramètres.
     * 
     * @param restClientBuilder Builder pour RestClient
     * @return SolrClient configuré
     */
    @Bean
    public SolrClient solrClient(RestClient.Builder restClientBuilder) {
        return new SolrClient(restClientBuilder);
    }
}

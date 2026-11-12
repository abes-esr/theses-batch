package fr.theses.batch.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

/**
 * Configuration explicite du DataSource H2 pour garantir la persistance
 * des métadonnées Spring Batch.
 * <p>
 * Utilise un chemin absolu /data/batchdb qui correspond au volume Docker monté
 * dans le container (via docker-compose.yml).
 * <p>
 * Sans cette configuration, Spring Batch peut tomber sur un ResourcelessJobRepository
 * (en mémoire) même si spring-boot-starter-jdbc est présent.
 */
@Configuration
@Profile("dist")
public class DataSourceConfig {

    @Bean
    public DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        // Chemin absolu pour correspondre au volume Docker /data
        dataSource.setUrl("jdbc:h2:file:/data/batchdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }
}

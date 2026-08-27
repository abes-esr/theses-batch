package fr.theses.batch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Application principale Spring Batch pour le traitement des thèses
 */
@SpringBootApplication
@EnableJpaRepositories("fr.theses.batch")
@EntityScan("fr.theses.batch")
public class ThesesBatch {
    
    public static void main(String[] args) {
        SpringApplication.run(ThesesBatch.class, args);
    }
}

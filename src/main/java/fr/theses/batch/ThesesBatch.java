package fr.theses.batch;

import fr.theses.batch.job.RunTimestamp;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Application principale Spring Batch pour le traitement des thèses
 */
@SpringBootApplication
public class ThesesBatch {
    
    public static void main(String[] args) {
        // Avant SpringApplication.run : le fichier de log et les résultats partagent cet horodatage
        RunTimestamp.initialize();
        SpringApplication.run(ThesesBatch.class, args);
    }
}

package fr.theses.batch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Application principale Spring Batch pour le traitement des thèses
 */
@SpringBootApplication
public class ThesesBatch {
    
    public static void main(String[] args) {
        SpringApplication.run(ThesesBatch.class, args);
    }
}

package fr.theses.batch.job;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Horodatage unique du run, figé au démarrage de l'application
 * Partagé par tous les writers pour que les fichiers d'un même run portent le même préfixe
 */
@Component
public class RunTimestamp {

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss");

    private final String value = LocalDateTime.now().format(TIMESTAMP_FORMATTER);

    /**
     * @return l'horodatage du run au format yyyy-MM-dd-HH-mm-ss
     */
    public String value() {
        return value;
    }
}

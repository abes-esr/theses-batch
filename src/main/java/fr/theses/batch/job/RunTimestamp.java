package fr.theses.batch.job;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Horodatage unique du run, partagé par le fichier de log et les fichiers de résultats
 * Fixé une seule fois dans main(), avant le démarrage de Spring, et publié en propriété système :
 * log4j2-spring.xml le lit via ${sys:app.run.timestamp}, ce bean via l'Environment Spring
 */
@Component
public class RunTimestamp {

    /**
     * Propriété système portant l'horodatage du run
     */
    public static final String PROPERTY = "app.run.timestamp";

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss");

    private final String value;

    public RunTimestamp(@Value("${" + PROPERTY + "}") String value) {
        this.value = value;
    }

    /**
     * Fixe l'horodatage du run s'il n'a pas été imposé au lancement (-Dapp.run.timestamp=...)
     * À appeler en tout premier dans main(), avant que Spring Boot ne charge log4j2-spring.xml
     */
    public static void initialize() {
        if (System.getProperty(PROPERTY) == null) {
            System.setProperty(PROPERTY, LocalDateTime.now().format(TIMESTAMP_FORMATTER));
        }
    }

    /**
     * @return l'horodatage du run au format yyyy-MM-dd-HH-mm-ss
     */
    public String value() {
        return value;
    }
}

package fr.theses.batch.support;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Capture les événements d'un logger pendant un test, tous niveaux confondus.
 * À utiliser dans un try-with-resources : le niveau du logger est rétabli à la fermeture.
 */
public final class LogCapture implements AutoCloseable {

    private final List<LogEvent> events = new CopyOnWriteArrayList<>();
    private final Logger logger;
    private final AbstractAppender appender;
    private final Level previousLevel;

    private LogCapture(Class<?> loggerClass) {
        this.logger = (Logger) LogManager.getLogger(loggerClass);
        this.previousLevel = logger.getLevel();
        this.appender = new AbstractAppender("capture-" + loggerClass.getSimpleName(), null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                events.add(event.toImmutable());
            }
        };
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.ALL);
    }

    public static LogCapture of(Class<?> loggerClass) {
        return new LogCapture(loggerClass);
    }

    /**
     * @return les messages capturés au niveau donné, dans l'ordre d'émission
     */
    public List<String> messages(Level level) {
        return events(level).stream()
                .map(event -> event.getMessage().getFormattedMessage())
                .toList();
    }

    /**
     * @return les événements capturés au niveau donné, dans l'ordre d'émission (pour en lire l'exception)
     */
    public List<LogEvent> events(Level level) {
        return events.stream()
                .filter(event -> event.getLevel() == level)
                .toList();
    }

    @Override
    public void close() {
        logger.removeAppender(appender);
        logger.setLevel(previousLevel);
        appender.stop();
    }
}

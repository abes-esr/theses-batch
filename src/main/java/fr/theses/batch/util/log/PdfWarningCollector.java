package fr.theses.batch.util.log;

import org.apache.logging.log4j.core.LogEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Regroupe par type les avertissements PDFBox / FontBox émis pendant le traitement d'un fichier PDF.
 * <p>
 * La collecte est propre au thread courant : {@link #start()} l'ouvre avant la lecture du PDF,
 * {@link #stop()} la ferme et rend le décompte. Hors collecte, {@link PdfWarningFilter} laisse
 * passer les événements normalement.
 */
public final class PdfWarningCollector {

    /** Un message par glyphe : le glyphe varie, seule la police caractérise le problème. */
    private static final Pattern NO_UNICODE_MAPPING = Pattern.compile("^No Unicode mapping for .+ in font (.+)$");
    private static final Pattern NUMBERS = Pattern.compile("\\d+");

    private static final ThreadLocal<Map<String, Integer>> CURRENT = new ThreadLocal<>();

    private PdfWarningCollector() {
    }

    /**
     * Ouvre une collecte sur le thread courant (en remplaçant une éventuelle collecte non fermée).
     */
    public static void start() {
        CURRENT.set(new LinkedHashMap<>());
    }

    /**
     * Ferme la collecte du thread courant.
     *
     * @return nombre d'avertissements par type, vide si aucune collecte n'était ouverte
     */
    public static Map<String, Integer> stop() {
        Map<String, Integer> counts = CURRENT.get();
        CURRENT.remove();
        return counts == null ? Map.of() : counts;
    }

    /**
     * Comptabilise l'événement si une collecte est ouverte sur le thread courant.
     *
     * @return true si l'événement a été comptabilisé, false s'il doit être journalisé normalement
     */
    static boolean collect(LogEvent event) {
        Map<String, Integer> counts = CURRENT.get();
        if (counts == null) {
            return false;
        }
        counts.merge(typeOf(event), 1, Integer::sum);
        return true;
    }

    private static String typeOf(LogEvent event) {
        String loggerName = event.getLoggerName();
        String simpleName = loggerName.substring(loggerName.lastIndexOf('.') + 1);
        String message = event.getMessage().getFormattedMessage();

        Matcher noUnicode = NO_UNICODE_MAPPING.matcher(message);
        String normalized = noUnicode.matches()
                ? "No Unicode mapping in font " + noUnicode.group(1)
                : NUMBERS.matcher(message).replaceAll("N");

        return event.getLevel() + " " + simpleName + " - " + normalized;
    }
}

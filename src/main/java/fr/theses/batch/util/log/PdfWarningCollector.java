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
 * <p>
 * Les messages qui nomment une police ne sont pas normalisés chiffre par chiffre (CMSY10 n'est pas
 * CMSY8) mais par nom de police : préfixe de sous-ensemble retiré, échappements #xx décodés, suffixe
 * d'encodage -Identity-H / -Identity-V retiré. Une même police garde ainsi le même type d'un fichier
 * à l'autre, et ses déclinaisons dans un même fichier sont comptées ensemble.
 */
public final class PdfWarningCollector {

    /** Un message par code : le code varie, seule la police caractérise le problème. */
    private static final Pattern NO_UNICODE_MAPPING = Pattern.compile("^No Unicode mapping for .+ in font (.+)$");
    /** « for base font X » (PDType1Font) ramené à « for X », comme dans les autres classes de police */
    private static final Pattern FALLBACK_FONT =
            Pattern.compile("^Using fallback font (\\S+) for (?:base font )?(CID-keyed TrueType font )?(.+)$");
    private static final Pattern NO_POSTSCRIPT_NAME =
            Pattern.compile("^No PostScript name data is provided for the font (.+)$");
    private static final Pattern NUMBERS = Pattern.compile("\\d+");

    /** Préfixe de sous-ensemble : six majuscules et « + » (ex. ABCDEF+Calibri) */
    private static final Pattern SUBSET_PREFIX = Pattern.compile("^[A-Z]{6}\\+");
    /** Échappement d'un caractère ASCII imprimable dans un nom PDF (#20 = espace) */
    private static final Pattern NAME_ESCAPE = Pattern.compile("#([2-6][0-9A-Fa-f]|7[0-9A-Ea-e])");
    private static final Pattern IDENTITY_SUFFIX = Pattern.compile("(?:-Identity-[HV])+$");

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
     * Comptabilise un avertissement émis par l'application elle-même pendant la lecture du PDF, pour
     * qu'il figure dans le récapitulatif du fichier plutôt que sur une ligne à part.
     *
     * @param type Type d'avertissement, au format « NIVEAU Classe - message »
     * @return true s'il a été comptabilisé, false s'il doit être journalisé normalement (pas de collecte ouverte)
     */
    public static boolean record(String type) {
        Map<String, Integer> counts = CURRENT.get();
        if (counts == null) {
            return false;
        }
        counts.merge(type, 1, Integer::sum);
        return true;
    }

    /**
     * Comptabilise l'événement si une collecte est ouverte sur le thread courant.
     *
     * @return true si l'événement a été comptabilisé, false s'il doit être journalisé normalement
     */
    static boolean collect(LogEvent event) {
        return CURRENT.get() != null && record(typeOf(event));
    }

    private static String typeOf(LogEvent event) {
        String loggerName = event.getLoggerName();
        String simpleName = loggerName.substring(loggerName.lastIndexOf('.') + 1);
        return event.getLevel() + " " + simpleName + " - " + normalize(event.getMessage().getFormattedMessage());
    }

    private static String normalize(String message) {
        Matcher matcher = NO_UNICODE_MAPPING.matcher(message);
        if (matcher.matches()) {
            return "No Unicode mapping in font " + fontName(matcher.group(1));
        }
        matcher = FALLBACK_FONT.matcher(message);
        if (matcher.matches()) {
            String cidKeyed = matcher.group(2) == null ? "" : matcher.group(2);
            return "Using fallback font " + matcher.group(1) + " for " + cidKeyed + fontName(matcher.group(3));
        }
        matcher = NO_POSTSCRIPT_NAME.matcher(message);
        if (matcher.matches()) {
            return "No PostScript name data is provided for the font " + fontName(matcher.group(1));
        }
        return NUMBERS.matcher(message).replaceAll("N");
    }

    /**
     * Nom de police sans préfixe de sous-ensemble, échappements décodés (plusieurs fois : certains
     * producteurs échappent l'échappement, #2320 → #20 → espace), sans suffixe -Identity-H.
     */
    public static String fontName(String name) {
        String decoded = SUBSET_PREFIX.matcher(name).replaceFirst("");
        for (String previous = null; !decoded.equals(previous); ) {
            previous = decoded;
            decoded = NAME_ESCAPE.matcher(decoded)
                    .replaceAll(escape -> Matcher.quoteReplacement(
                            String.valueOf((char) Integer.parseInt(escape.group(1), 16))));
        }
        String stripped = IDENTITY_SUFFIX.matcher(decoded).replaceFirst("");
        return stripped.isEmpty() ? decoded : stripped;
    }
}

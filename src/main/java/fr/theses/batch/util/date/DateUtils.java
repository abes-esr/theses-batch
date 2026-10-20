package fr.theses.batch.util.date;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Utilitaires pour la manipulation des dates
 */
public class DateUtils {
    
    private static final DateTimeFormatter DEFAULT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FILE_NAME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss");
    
    /**
     * Formate une date pour l'affichage
     * 
     * @param dateTime Date à formater
     * @return Date formatée
     */
    public static String format(LocalDateTime dateTime) {
        return dateTime.format(DEFAULT_FORMATTER);
    }
    
    /**
     * Formate une date pour les noms de fichiers
     * 
     * @param dateTime Date à formater
     * @return Date formatée pour les noms de fichiers
     */
    public static String formatForFileName(LocalDateTime dateTime) {
        return dateTime.format(FILE_NAME_FORMATTER);
    }
    
    /**
     * Obtient la date actuelle formatée
     * 
     * @return Date actuelle formatée
     */
    public static String getCurrentDateTime() {
        return format(LocalDateTime.now());
    }
    
    /**
     * Obtient la date actuelle formatée pour les noms de fichiers
     * 
     * @return Date actuelle formatée pour les noms de fichiers
     */
    public static String getCurrentDateTimeForFileName() {
        return formatForFileName(LocalDateTime.now());
    }

    /**
     * Durée lisible : en millisecondes sous la seconde, à la seconde près sous l'heure, à la minute
     * près au-delà (ex. « 850 ms », « 27 min 11 s », « 1 h 28 min »). Une durée nulle ou négative
     * donne « 0 s ».
     *
     * @param nanos Durée en nanosecondes
     * @return Durée formatée
     */
    public static String formatDuration(long nanos) {
        if (nanos > 0 && nanos < 999_500_000L) {
            return Math.round(nanos / 1e6) + " ms";
        }
        long seconds = Math.max(0, Math.round(nanos / 1e9));
        long hours = seconds / 3600;
        long minutes = seconds % 3600 / 60;
        if (hours > 0) {
            return hours + " h " + minutes + " min";
        }
        if (minutes > 0) {
            return minutes + " min " + seconds % 60 + " s";
        }
        return seconds + " s";
    }
}

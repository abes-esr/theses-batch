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
}

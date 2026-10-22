package fr.theses.batch.business.anr.service;

import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Service pour la recherche de motifs ANR dans les fichiers PDF
 */
@Service
public class ANRSearchService {

    /**
     * Valide si un nom de fichier doit être exclu
     * <p>
     * Les mots-clés ne sont comparés qu'au nom du fichier ; pour exclure un répertoire, voir
     * {@link #shouldExcludeDirectory(String, List)}.
     *
     * @param fileName Nom du fichier
     * @param excludeKeywords Liste des mots-clés à exclure
     * @return true si le fichier doit être exclu
     */
    public boolean shouldExcludeFile(String fileName, List<String> excludeKeywords) {
        if (fileName == null || !fileName.toLowerCase().endsWith(".pdf")) {
            return true;
        }

        String fileNameLower = fileName.toLowerCase();
        return excludeKeywords.stream()
                .anyMatch(keyword -> fileNameLower.contains(keyword.toLowerCase()));
    }

    /**
     * Convertit la valeur de {@code app.anr.exclude-dirs} en motifs de répertoires.
     * <p>
     * Motifs séparés par des virgules, en syntaxe glob, comparés au chemin du répertoire relatif à
     * {@code input-dir}, séparateur {@code /}, sensibles à la casse : {@code *} remplace tout ou partie
     * d'un nom, {@code ?} un caractère, {@code **} un nombre quelconque de niveaux. Exemple :
     * <code>&#42;/&#42;/temp</code> désigne le répertoire {@code temp} de chaque thèse
     * ({@code ETAB/THESE_x/temp}). Les entrées vides sont ignorées ; les {@code /} de début et de fin
     * et les {@code \} sont tolérés.
     *
     * @param excludeDirs Valeur de la clé, éventuellement vide
     * @return Motifs compilés, vide si aucun répertoire n'est exclu
     */
    public List<Pattern> parseExcludedDirectories(String excludeDirs) {
        if (excludeDirs == null) {
            return List.of();
        }
        return Arrays.stream(excludeDirs.split(","))
                .map(glob -> trimSlashes(glob.trim().replace('\\', '/')))
                .filter(glob -> !glob.isEmpty())
                .map(ANRSearchService::globToPattern)
                .toList();
    }

    /**
     * Valide si un répertoire doit être exclu : le parcours n'y descend alors pas du tout.
     *
     * @param relativePath Chemin du répertoire relatif à input-dir, séparateur {@code /}
     * @param excludeDirs  Motifs issus de {@link #parseExcludedDirectories(String)}
     * @return true si le chemin correspond à l'un des motifs
     */
    public boolean shouldExcludeDirectory(String relativePath, List<Pattern> excludeDirs) {
        return excludeDirs.stream().anyMatch(pattern -> pattern.matcher(relativePath).matches());
    }

    private static String trimSlashes(String glob) {
        int start = 0;
        int end = glob.length();
        while (start < end && glob.charAt(start) == '/') {
            start++;
        }
        while (end > start && glob.charAt(end - 1) == '/') {
            end--;
        }
        return glob.substring(start, end);
    }

    private static Pattern globToPattern(String glob) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*' && i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                i++;
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '/') {
                    // « **/ » : zéro ou plusieurs niveaux, pour que « **/temp » vise aussi « temp » à la racine
                    i++;
                    regex.append("(?:.*/)?");
                } else {
                    regex.append(".*");
                }
            } else if (c == '*') {
                regex.append("[^/]*");
            } else if (c == '?') {
                regex.append("[^/]");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString());
    }

    /**
     * Extrait le nom du fichier à partir du chemin complet
     *
     * @param filePath Chemin complet du fichier
     * @return Nom du fichier
     */
    public String extractFileName(String filePath) {
        if (filePath == null) {
            return "";
        }
        int lastSeparator = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
        return lastSeparator >= 0 ? filePath.substring(lastSeparator + 1) : filePath;
    }
}

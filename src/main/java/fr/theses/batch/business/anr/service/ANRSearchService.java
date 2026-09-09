package fr.theses.batch.business.anr.service;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Service pour la recherche de motifs ANR dans les fichiers PDF
 */
@Service
public class ANRSearchService {

    /**
     * Valide si un nom de fichier doit être exclu
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

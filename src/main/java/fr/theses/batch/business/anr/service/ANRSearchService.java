package fr.theses.batch.business.anr.service;

import fr.theses.batch.business.anr.model.dto.ANRMatchDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Service pour la recherche de motifs ANR dans les fichiers PDF
 */
@Service
public class ANRSearchService {
    
    private final Pattern anrPattern;
    private final int maxPages;
    
    public ANRSearchService(
            @Value("${app.anr.pattern}") String pattern,
            @Value("${app.nb-pages:0}") int maxPages) {
        this.anrPattern = Pattern.compile(pattern);
        this.maxPages = maxPages;
    }
    
    /**
     * Recherche les motifs ANR dans le texte fourni
     * 
     * @param text Texte à analyser
     * @return Liste des correspondances trouvées
     */
    public List<String> findANRMatches(String text) {
        return anrPattern.matcher(text).results()
                .map(match -> match.group())
                .distinct()
                .toList();
    }
    
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
    
    /**
     * Crée un DTO de résultat de recherche
     * 
     * @param filePath Chemin du fichier
     * @param fileName Nom du fichier
     * @param matches Liste des correspondances
     * @param pagesAnalyzed Nombre de pages analysées
     * @param totalPages Nombre total de pages
     * @param processingTime Durée du traitement
     * @param errorMessage Message d'erreur
     * @return DTO de résultat
     */
    public ANRMatchDTO createMatchDTO(String filePath, String fileName, List<String> matches, 
                                    int pagesAnalyzed, int totalPages, double processingTime, 
                                    String errorMessage) {
        ANRMatchDTO dto = new ANRMatchDTO();
        dto.setFilePath(filePath);
        dto.setFileName(fileName);
        dto.setMatches(matches != null ? new ArrayList<>(matches) : new ArrayList<>());
        dto.setPagesAnalyzed(pagesAnalyzed);
        dto.setTotalPages(totalPages);
        dto.setProcessingTime(processingTime);
        dto.setErrorMessage(errorMessage);
        return dto;
    }
}

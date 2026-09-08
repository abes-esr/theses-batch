package fr.theses.batch.business.anr.service;

import fr.theses.batch.business.anr.model.dto.ANRMatchDTO;
import jakarta.annotation.PostConstruct;
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
    @Value("${app.anr.pattern:''}")
    private String anrPatternSource;
    @Value("${app.anr.nb-pages:50}")
    private int maxPages;
    private Pattern anrPattern;

    @PostConstruct
    public void init() {
        this.anrPattern = Pattern.compile(anrPatternSource);
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
        return new ANRMatchDTO(filePath, fileName, matches, new ArrayList<>(), 
                              pagesAnalyzed, totalPages, processingTime, errorMessage);
    }
}

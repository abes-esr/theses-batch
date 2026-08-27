package fr.theses.batch.job.reader;

import fr.theses.batch.business.anr.model.dto.ANRMatchDTO;
import fr.theses.batch.business.anr.service.ANRSearchService;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.NonTransientResourceException;
import org.springframework.batch.item.ParseException;
import org.springframework.batch.item.UnexpectedInputException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Reader pour le traitement ANR
 * Lit les fichiers PDF dans les répertoires spécifiés
 */
@Component("anrReader")
public class ANRReader implements ItemReader<ANRMatchDTO> {
    
    private final ANRSearchService anrSearchService;
    private final String rootDir;
    private final List<String> excludeKeywords;
    
    private Iterator<File> fileIterator;
    private int filesProcessed = 0;
    private int maxFiles;
    private int offset;
    
    @Autowired
    public ANRReader(ANRSearchService anrSearchService,
                     @Value("${app.root-dir}") String rootDir,
                     @Value("${app.exclude-keywords}") String excludeKeywords,
                     @Value("${app.max-files:10000}") int maxFiles,
                     @Value("${app.offset:0}") int offset) {
        this.anrSearchService = anrSearchService;
        this.rootDir = rootDir;
        this.excludeKeywords = Arrays.asList(excludeKeywords.split(","));
        this.maxFiles = maxFiles;
        this.offset = offset;
        
        initializeFileIterator();
    }
    
    public ANRSearchService getAnrSearchService() {
        return anrSearchService;
    }
    
    /**
     * Initialise l'itérateur de fichiers
     */
    private void initializeFileIterator() {
        try {
            List<File> allFiles = findAllPdfFiles();
            fileIterator = allFiles.iterator();
            
            // Avance jusqu'à l'offset
            for (int i = 0; i < offset && fileIterator.hasNext(); i++) {
                fileIterator.next();
                filesProcessed++;
            }
        } catch (IOException e) {
            throw new NonTransientResourceException("Failed to initialize file iterator", e);
        }
    }
    
    /**
     * Trouve tous les fichiers PDF dans la structure de répertoires
     * 
     * @return Liste des fichiers PDF triés
     * @throws IOException En cas d'erreur d'accès aux fichiers
     */
    private List<File> findAllPdfFiles() throws IOException {
        List<File> pdfFiles = new ArrayList<>();
        
        Path rootPath = Path.of(rootDir);
        if (!Files.exists(rootPath)) {
            throw new IOException("Root directory does not exist: " + rootDir);
        }
        
        try (Stream<Path> paths = Files.walk(rootPath)) {
            paths.filter(Files::isRegularFile)
                .filter(path -> path.toString().toLowerCase().endsWith(".pdf"))
                .map(Path::toFile)
                .filter(file -> !anrSearchService.shouldExcludeFile(file.getName(), excludeKeywords))
                .sorted(Comparator.comparing(File::getAbsolutePath))
                .forEach(pdfFiles::add);
        }
        
        return pdfFiles;
    }
    
    @Override
    public ANRMatchDTO read() throws Exception, UnexpectedInputException, ParseException, NonTransientResourceException {
        if (!fileIterator.hasNext() || filesProcessed >= maxFiles) {
            return null;
        }
        
        File file = fileIterator.next();
        filesProcessed++;
        
        String filePath = file.getAbsolutePath();
        String fileName = anrSearchService.extractFileName(filePath);
        
        // Crée un DTO avec les informations de base
        ANRMatchDTO dto = new ANRMatchDTO();
        dto.setFilePath(filePath);
        dto.setFileName(fileName);
        dto.setMatches(new ArrayList<>());
        
        return dto;
    }
}

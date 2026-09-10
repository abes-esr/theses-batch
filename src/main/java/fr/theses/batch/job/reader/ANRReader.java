package fr.theses.batch.job.reader;

import fr.theses.batch.business.anr.model.dto.ANRMatchDTO;
import fr.theses.batch.business.anr.service.ANRSearchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.infrastructure.item.ItemReader;
import org.springframework.batch.infrastructure.item.NonTransientResourceException;
import org.springframework.batch.infrastructure.item.ParseException;
import org.springframework.batch.infrastructure.item.UnexpectedInputException;
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
@Slf4j
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
                     @Value("${app.anr.root-dir}") String rootDir,
                     @Value("${app.anr.exclude-keywords}") String excludeKeywords,
                     @Value("${app.anr.max-files:10000}") int maxFiles,
                     @Value("${app.anr.offset:0}") int offset) {
        this.anrSearchService = anrSearchService;
        this.rootDir = rootDir;
        this.excludeKeywords = Arrays.asList(excludeKeywords.split(","));
        this.maxFiles = maxFiles;
        this.offset = offset;
        
        log.info("Initialisation ANRReader avec rootDir={}, maxFiles={}, offset={}", rootDir, maxFiles, offset);
        initializeFileIterator();
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
            log.info("Itérateur de fichiers initialisé avec {} fichiers PDF, offset={}", allFiles.size(), offset);
        } catch (IOException e) {
            log.error("Erreur lors de l'initialisation de l'itérateur de fichiers", e);
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
            log.error("Le répertoire racine n'existe pas : {}", rootDir);
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

        log.info("Nombre de fichiers PDF trouvés : {}", pdfFiles.size());
        
        return pdfFiles;
    }
    
    @Override
    public ANRMatchDTO read() throws Exception, UnexpectedInputException, ParseException, NonTransientResourceException {
        if (!fileIterator.hasNext() || filesProcessed >= maxFiles) {
            if (!fileIterator.hasNext()) {
                log.info("Fin de la lecture : aucun fichier supplémentaire disponible");
            } else {
                log.info("Limite de fichiers atteinte : {}/{}", filesProcessed, maxFiles);
            }
            return null;
        }
        
        File file = fileIterator.next();
        filesProcessed++;
        
        String filePath = file.getAbsolutePath();
        String fileName = anrSearchService.extractFileName(filePath);
        
        log.debug("Traitement du fichier {}/{}: {}", filesProcessed, maxFiles, fileName);
        
        // Crée un DTO avec les informations de base
        return new ANRMatchDTO(filePath, fileName, new ArrayList<>(), new ArrayList<>(), 0, 0, 0.0, null);
    }
}

package fr.theses.batch.job.reader;

import fr.theses.batch.business.anr.model.dto.ANRMatchDTO;
import fr.theses.batch.business.anr.service.ANRSearchService;
import fr.theses.batch.util.date.DateUtils;
import fr.theses.batch.util.file.PdfFileScanner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.infrastructure.item.ItemReader;
import org.springframework.batch.infrastructure.item.NonTransientResourceException;
import org.springframework.batch.infrastructure.item.ParseException;
import org.springframework.batch.infrastructure.item.UnexpectedInputException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Pattern;

/**
 * Reader pour le traitement ANR
 * Lit les fichiers PDF dans les répertoires spécifiés
 */
@Component("anrReader")
@Slf4j
public class ANRReader implements ItemReader<ANRMatchDTO> {

    private final ANRSearchService anrSearchService;
    private final String inputDir;
    private final List<String> excludeKeywords;
    private final String excludeDirs;
    private final List<Pattern> excludeDirPatterns;
    private final int scanThreads;

    private Iterator<String> fileIterator;
    private int filesProcessed = 0;
    private int maxFiles;
    private int offset;
    private final String startFile;
    private int totalToRead;

    @Autowired
    public ANRReader(ANRSearchService anrSearchService,
                     @Value("${app.anr.input-dir:/input}") String inputDir,
                     @Value("${app.anr.exclude-keywords:}") String excludeKeywords,
                     @Value("${app.anr.max-files:10000}") int maxFiles,
                     @Value("${app.anr.offset:0}") int offset,
                     @Value("${app.anr.scan-threads:8}") int scanThreads,
                     @Value("${app.anr.start-file:}") String startFile,
                     @Value("${app.anr.exclude-dirs:}") String excludeDirs) {
        this.anrSearchService = anrSearchService;
        this.inputDir = inputDir;
        // Mis en minuscules une fois pour toutes ; une entrée vide exclurait tous les fichiers
        this.excludeKeywords = Arrays.stream(excludeKeywords.split(","))
                .filter(keyword -> !keyword.isEmpty())
                .map(String::toLowerCase)
                .toList();
        this.excludeDirs = excludeDirs;
        this.excludeDirPatterns = anrSearchService.parseExcludedDirectories(excludeDirs);
        this.maxFiles = maxFiles;
        this.offset = offset;
        this.scanThreads = scanThreads;
        this.startFile = startFile;

        log.info("Initialisation ANRReader avec inputDir={}, maxFiles={}, offset={}, scanThreads={}, excludeDirs={}",
                inputDir, maxFiles, offset, scanThreads, excludeDirPatterns.isEmpty() ? "aucun" : excludeDirs);
        initializeFileIterator();
    }

    /**
     * Initialise l'itérateur de fichiers
     */
    private void initializeFileIterator() {
        try {
            List<String> allFiles = findAllPdfFiles();
            fileIterator = allFiles.iterator();
            // Éléments déjà tirés de l'itérateur, que read() ne rendra donc pas
            int consumed = 0;

            // Si startFile est spécifié, trouver son index et sauter jusqu'à lui
            if (startFile != null && !startFile.isEmpty()) {
                boolean found = false;
                for (int i = 0; fileIterator.hasNext(); i++) {
                    String currentFile = fileIterator.next();
                    consumed++;
                    if (currentFile.equals(startFile) || currentFile.endsWith(startFile)) {
                        found = true;
                        log.info("Reprise depuis le fichier : {}", currentFile);
                        break;
                    }
                    filesProcessed++;
                }
                if (!found) {
                    log.warn("Fichier de reprise non trouvé : {}. Reprise depuis le début.", startFile);
                    // Réinitialiser l'itérateur et appliquer l'offset
                    fileIterator = allFiles.iterator();
                    filesProcessed = 0;
                    consumed = 0;
                }
            } else {
                // Avance jusqu'à l'offset
                for (int i = 0; i < offset && fileIterator.hasNext(); i++) {
                    fileIterator.next();
                    consumed++;
                    filesProcessed++;
                }
            }
            // Fichiers que read() rendra : ceux qui restent dans l'itérateur, bornés par max-files
            // (une position dans la liste, fichiers sautés compris)
            totalToRead = Math.max(0, Math.min(allFiles.size() - consumed, maxFiles - filesProcessed));
            log.info("Itérateur de fichiers initialisé avec {} fichiers PDF, offset={}, startFile={}, {} à traiter",
                    allFiles.size(), offset, startFile != null ? startFile : "none", totalToRead);
        } catch (IOException e) {
            log.error("Erreur lors de l'initialisation de l'itérateur de fichiers", e);
            throw new NonTransientResourceException("Failed to initialize file iterator", e);
        }
    }

    /**
     * Trouve tous les fichiers PDF dans la structure de répertoires
     *
     * @return Chemins absolus des fichiers PDF, triés
     * @throws IOException En cas d'erreur d'accès aux fichiers
     */
    private List<String> findAllPdfFiles() throws IOException {
        Path inputPath = Path.of(inputDir).toAbsolutePath();
        if (!Files.exists(inputPath)) {
            log.error("Le répertoire d'entrée (app.anr.input-dir) n'existe pas : {}", inputDir);
            throw new IOException("Input directory does not exist: " + inputDir);
        }

        // Tri sur la chaîne du chemin absolu, calculée une seule fois : c'est l'ordre auquel s'applique l'offset
        long start = System.nanoTime();
        LongAdder excludedDirectories = new LongAdder();
        List<String> pdfFiles = PdfFileScanner.scan(inputPath,
                        directory -> {
                            if (anrSearchService.shouldExcludeDirectory(directory, excludeDirPatterns)) {
                                excludedDirectories.increment();
                                return false;
                            }
                            return true;
                        },
                        name -> !anrSearchService.shouldExcludeFile(name, excludeKeywords), scanThreads)
                .stream()
                .map(Path::toString)
                .sorted()
                .toList();

        log.info("Nombre de fichiers PDF trouvés : {} (parcours en {}, {} threads)",
                pdfFiles.size(), DateUtils.formatDuration(System.nanoTime() - start), scanThreads);
        if (!excludeDirPatterns.isEmpty()) {
            // La liste triée n'est plus celle d'un run sans exclusion : les offsets ne sont pas transposables
            log.info("Répertoires exclus (app.anr.exclude-dirs={}) : {}, non parcourus", excludeDirs,
                    excludedDirectories.sum());
        }

        return pdfFiles;
    }

    /**
     * Nombre de fichiers que read() rendra sur l'ensemble du run, connu dès la construction :
     * sert de dénominateur au suivi de progression.
     */
    public int getTotalToRead() {
        return totalToRead;
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

        String filePath = fileIterator.next();
        filesProcessed++;

        String fileName = anrSearchService.extractFileName(filePath);

        log.debug("Traitement du fichier {}/{}: {}", filesProcessed, maxFiles, fileName);

        // Crée un DTO avec les informations de base
        return new ANRMatchDTO(filePath, fileName, new ArrayList<>(), 0, 0, 0.0, null, null, null, (LocalDate) null);
    }
}

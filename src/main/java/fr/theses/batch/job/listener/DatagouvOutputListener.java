package fr.theses.batch.job.listener;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Range les fichiers d'export du job Datagouv dans {output-dir}/datagouv :
 * <ul>
 *     <li>tmp : fichiers du run en cours d'écriture ;</li>
 *     <li>success : uniquement les fichiers de la dernière publication réussie sur data.gouv.fr ;</li>
 *     <li>test : uniquement les fichiers du dernier run réussi sans publication (mode test ou
 *     téléversement désactivé), pour ne jamais remplacer ceux de la dernière publication ;</li>
 *     <li>errors : les fichiers de tous les runs en échec, vidé à chaque publication réussie.</li>
 * </ul>
 * Le log du run est copié à côté de ses fichiers. Les fichiers laissés dans tmp par un run interrompu
 * sans fin de job (conteneur arrêté, OOM) sont déplacés dans errors au début du run suivant.
 * <p>
 * Les logs des runs ({timestamp}_batch.log, à la racine de output-dir) sont créés à chaque lancement,
 * y compris quand la règle des 6 mois écarte le job : {@link #purgeOldLogs()} supprime ceux plus
 * anciens que la durée de rétention.
 */
@Slf4j
public class DatagouvOutputListener implements JobExecutionListener {

    static final String TMP = "tmp";
    static final String SUCCESS = "success";
    static final String TEST = "test";
    static final String ERRORS = "errors";

    private static final Pattern RUN_LOG = Pattern.compile("\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-\\d{2}_batch\\.log");

    private final Path outputDir;
    private final Path datagouvDir;
    private final String runTimestamp;
    private final Duration logRetention;
    private final boolean publishing;

    /**
     * @param outputDir    Répertoire de sortie global (app.output-dir).
     * @param runTimestamp Horodatage du run au format yyyy-MM-dd-HH-mm-ss.
     * @param logRetention Âge au-delà duquel les logs des runs sont supprimés.
     * @param publishing   Vrai si un run réussi publie sur data.gouv.fr (téléversement activé, hors mode
     *                     test) : même configuration que DatagouvUploadTasklet.
     */
    public DatagouvOutputListener(String outputDir, String runTimestamp, Duration logRetention, boolean publishing) {
        this.outputDir = Path.of(outputDir);
        this.datagouvDir = this.outputDir.resolve("datagouv");
        this.runTimestamp = runTimestamp;
        this.logRetention = logRetention;
        this.publishing = publishing;
    }

    /**
     * Répertoire où les writers et tasklets du job écrivent les fichiers du run en cours.
     */
    public static File tmpDir(String outputDir) {
        return Path.of(outputDir, "datagouv", TMP).toFile();
    }

    @Override
    public void beforeJob(@NonNull JobExecution jobExecution) {
        try {
            Files.createDirectories(dir(TMP));
            if (!listFiles(dir(TMP)).isEmpty()) {
                log.warn("Fichiers d'un run interrompu trouvés dans {} : déplacés dans {}.", dir(TMP), dir(ERRORS));
                moveFiles(dir(TMP), dir(ERRORS));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Impossible de préparer le répertoire " + dir(TMP), e);
        }
    }

    @Override
    public void afterJob(@NonNull JobExecution jobExecution) {
        String target;
        if (jobExecution.getStatus() != BatchStatus.COMPLETED) {
            target = ERRORS;
        } else {
            target = publishing ? SUCCESS : TEST;
        }
        Path destination = dir(target);
        try {
            if (!ERRORS.equals(target)) {
                deleteFiles(destination);
            }
            moveFiles(dir(TMP), destination);
            if (SUCCESS.equals(target)) {
                deleteFiles(dir(ERRORS));
            }
            log.info("Fichiers du run {} ({}) rangés dans {}.", runTimestamp, jobExecution.getStatus(), destination);
            copyRunLog(destination);
        } catch (IOException e) {
            // Le statut du job n'est pas modifié : les fichiers restent dans tmp, rangés au run suivant
            log.error("Impossible de ranger les fichiers du run {} dans {} : ils restent dans {}.",
                    runTimestamp, destination, dir(TMP), e);
        }
    }

    /**
     * Supprime les logs des runs plus anciens que la durée de rétention, à la racine de output-dir.
     */
    public void purgeOldLogs() {
        Instant limit = Instant.now().minus(logRetention);
        try (Stream<Path> files = Files.list(outputDir)) {
            List<Path> oldLogs = files
                    .filter(path -> RUN_LOG.matcher(path.getFileName().toString()).matches())
                    .filter(path -> !path.getFileName().toString().startsWith(runTimestamp))
                    .filter(path -> lastModified(path).isBefore(limit))
                    .toList();
            for (Path oldLog : oldLogs) {
                Files.deleteIfExists(oldLog);
            }
            if (!oldLogs.isEmpty()) {
                log.info("{} log(s) de plus de {} supprimé(s) dans {}.", oldLogs.size(), logRetention, outputDir);
            }
        } catch (IOException | UncheckedIOException e) {
            log.warn("Purge des anciens logs impossible dans {} : {}", outputDir, e.getMessage());
        }
    }

    private Path dir(String name) {
        return datagouvDir.resolve(name);
    }

    /**
     * Copie le log du run (toujours en cours d'écriture) à côté de ses fichiers d'export.
     */
    private void copyRunLog(Path destination) throws IOException {
        Path runLog = outputDir.resolve(runTimestamp + "_batch.log");
        if (Files.exists(runLog)) {
            Files.copy(runLog, destination.resolve(runLog.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void moveFiles(Path source, Path destination) throws IOException {
        Files.createDirectories(destination);
        for (Path file : listFiles(source)) {
            Files.move(file, destination.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteFiles(Path directory) throws IOException {
        for (Path file : listFiles(directory)) {
            Files.delete(file);
        }
    }

    private static List<Path> listFiles(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(Files::isRegularFile).toList();
        }
    }

    private static Instant lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

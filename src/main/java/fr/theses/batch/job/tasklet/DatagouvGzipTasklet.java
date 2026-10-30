package fr.theses.batch.job.tasklet;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.jspecify.annotations.NonNull;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.zip.GZIPOutputStream;

/**
 * Tasklet Spring Batch compressant le fichier JSONL généré en {nom}.jsonl.gz, à côté de l'original.
 * Le fichier est lu et compressé en flux : la mémoire utilisée ne dépend pas de sa taille.
 */
@Slf4j
public class DatagouvGzipTasklet implements Tasklet {

    private final File source;
    private final File target;

    /**
     * @param outputDir    Répertoire local contenant les fichiers générés.
     * @param runTimestamp Horodatage du run au format yyyy-MM-dd-HH-mm-ss.
     */
    public DatagouvGzipTasklet(String outputDir, String runTimestamp) {
        this.source = new File(outputDir, runTimestamp + "_datagouv_results.jsonl");
        this.target = new File(outputDir, runTimestamp + "_datagouv_results.jsonl.gz");
    }

    @Override
    public RepeatStatus execute(@NonNull StepContribution contribution, @NonNull ChunkContext chunkContext) throws IOException {
        if (!source.exists()) {
            throw new IllegalStateException("Le fichier " + source.getAbsolutePath() + " à compresser n'existe pas.");
        }

        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(target.toPath()), 64 * 1024)) {
            Files.copy(source.toPath(), out);
        }

        log.info("Fichier {} compressé en {} ({} -> {} octets).",
                source.getName(), target.getName(), source.length(), target.length());
        return RepeatStatus.FINISHED;
    }
}

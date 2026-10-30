package fr.theses.batch.job.listener;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires de DatagouvOutputListener.
 */
class DatagouvOutputListenerTest {

    private static final String RUN = "2026-03-04-05-06-07";
    private static final String PREVIOUS_RUN = "2026-01-01-02-00-00";

    @TempDir
    Path outputDir;

    /** Run qui publie sur data.gouv.fr (téléversement activé, hors mode test). */
    private DatagouvOutputListener listener;
    /** Run sans publication (mode test ou téléversement désactivé). */
    private DatagouvOutputListener testListener;
    private Path tmp;
    private Path success;
    private Path test;
    private Path errors;

    @BeforeEach
    void setUp() {
        listener = new DatagouvOutputListener(outputDir.toString(), RUN, Duration.ofDays(30), true);
        testListener = new DatagouvOutputListener(outputDir.toString(), RUN, Duration.ofDays(30), false);
        Path datagouv = outputDir.resolve("datagouv");
        tmp = datagouv.resolve(DatagouvOutputListener.TMP);
        success = datagouv.resolve(DatagouvOutputListener.SUCCESS);
        test = datagouv.resolve(DatagouvOutputListener.TEST);
        errors = datagouv.resolve(DatagouvOutputListener.ERRORS);
    }

    private static JobExecution execution(BatchStatus status) {
        JobExecution execution = mock(JobExecution.class);
        when(execution.getStatus()).thenReturn(status);
        return execution;
    }

    private static Path write(Path directory, String name) throws IOException {
        Files.createDirectories(directory);
        return Files.writeString(directory.resolve(name), name);
    }

    private void writeRunExports() throws IOException {
        write(tmp, RUN + "_datagouv_results.csv");
        write(tmp, RUN + "_datagouv_results.jsonl");
    }

    @Test
    void tmpDir_estSousDatagouv() {
        assertThat(DatagouvOutputListener.tmpDir(outputDir.toString()).toPath()).isEqualTo(tmp);
    }

    @Test
    void beforeJob_creeTmpEtDeplaceLesRestesDUnRunInterrompuDansErrors() throws IOException {
        write(tmp, PREVIOUS_RUN + "_datagouv_results.jsonl");

        listener.beforeJob(mock(JobExecution.class));

        assertThat(tmp).isEmptyDirectory();
        assertThat(errors.resolve(PREVIOUS_RUN + "_datagouv_results.jsonl")).exists();
    }

    @Test
    void afterJob_publicationReussie_remplaceSuccessEtVideErrors() throws IOException {
        write(success, PREVIOUS_RUN + "_datagouv_results.csv");
        write(errors, PREVIOUS_RUN + "_datagouv_results.json");
        write(outputDir, RUN + "_batch.log");
        writeRunExports();

        listener.afterJob(execution(BatchStatus.COMPLETED));

        assertThat(success).isDirectoryContaining(path -> path.endsWith(RUN + "_datagouv_results.csv"))
                .isDirectoryContaining(path -> path.endsWith(RUN + "_datagouv_results.jsonl"))
                .isDirectoryContaining(path -> path.endsWith(RUN + "_batch.log"))
                .isDirectoryNotContaining(path -> path.getFileName().toString().startsWith(PREVIOUS_RUN));
        assertThat(errors).isEmptyDirectory();
        assertThat(tmp).isEmptyDirectory();
        // Le log d'origine reste en place : il est encore en cours d'écriture
        assertThat(outputDir.resolve(RUN + "_batch.log")).exists();
    }

    @Test
    void afterJob_echec_ajouteLesFichiersAErrorsSansToucherSuccess() throws IOException {
        write(success, PREVIOUS_RUN + "_datagouv_results.csv");
        write(errors, PREVIOUS_RUN + "_datagouv_results.json");
        writeRunExports();

        listener.afterJob(execution(BatchStatus.FAILED));

        assertThat(success.resolve(PREVIOUS_RUN + "_datagouv_results.csv")).exists();
        assertThat(errors.resolve(PREVIOUS_RUN + "_datagouv_results.json")).exists();
        assertThat(errors.resolve(RUN + "_datagouv_results.csv")).exists();
        assertThat(tmp).isEmptyDirectory();
    }

    @Test
    void afterJob_runReussiSansPublication_vaDansTestSansToucherSuccessNiErrors() throws IOException {
        write(success, PREVIOUS_RUN + "_datagouv_results.csv");
        write(errors, PREVIOUS_RUN + "_datagouv_results.json");
        write(test, PREVIOUS_RUN + "_datagouv_results.jsonl");
        writeRunExports();

        testListener.afterJob(execution(BatchStatus.COMPLETED));

        assertThat(test.resolve(RUN + "_datagouv_results.csv")).exists();
        assertThat(test.resolve(PREVIOUS_RUN + "_datagouv_results.jsonl")).doesNotExist();
        assertThat(success.resolve(PREVIOUS_RUN + "_datagouv_results.csv")).exists();
        assertThat(errors.resolve(PREVIOUS_RUN + "_datagouv_results.json")).exists();
    }

    @Test
    void purgeOldLogs_supprimeUniquementLesLogsDeRunsTropAnciens() throws IOException {
        Path oldLog = write(outputDir, PREVIOUS_RUN + "_batch.log");
        Files.setLastModifiedTime(oldLog, FileTime.from(Instant.now().minus(Duration.ofDays(31))));
        Path recentLog = write(outputDir, "2026-03-01-02-00-00_batch.log");
        Path currentLog = write(outputDir, RUN + "_batch.log");
        Files.setLastModifiedTime(currentLog, FileTime.from(Instant.now().minus(Duration.ofDays(31))));
        Path otherFile = write(outputDir, "notes.log");
        Files.setLastModifiedTime(otherFile, FileTime.from(Instant.now().minus(Duration.ofDays(31))));

        listener.purgeOldLogs();

        assertThat(oldLog).doesNotExist();
        assertThat(recentLog).exists();
        assertThat(currentLog).exists();
        assertThat(otherFile).exists();
    }
}

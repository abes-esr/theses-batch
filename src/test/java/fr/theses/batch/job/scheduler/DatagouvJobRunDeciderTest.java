package fr.theses.batch.job.scheduler;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.NoSuchJobException;
import org.springframework.batch.core.repository.JobRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires de DatagouvJobRunDecider.
 */
class DatagouvJobRunDeciderTest {

    private final JobRepository jobRepository = mock(JobRepository.class);
    private final String jobName = "thesesDumpDataGouvJob";

    private static final JobParameters PUBLICATION = params("false", "true");

    private static JobParameters params(String modeTest, String uploadEnabled) {
        return new JobParametersBuilder()
                .addString(DatagouvJobRunDecider.PARAM_MODE_TEST, modeTest, false)
                .addString(DatagouvJobRunDecider.PARAM_UPLOAD_ENABLED, uploadEnabled, false)
                .toJobParameters();
    }

    private static JobExecution execution(BatchStatus status, LocalDateTime endTime, JobParameters params) {
        JobExecution execution = mock(JobExecution.class);
        when(execution.getStatus()).thenReturn(status);
        when(execution.getEndTime()).thenReturn(endTime);
        when(execution.getJobParameters()).thenReturn(params);
        return execution;
    }

    /**
     * Historique : une instance par exécution, la plus récente en premier.
     */
    private void history(JobExecution... executions) throws Exception {
        List<JobInstance> instances = new ArrayList<>();
        for (int i = 0; i < executions.length; i++) {
            JobInstance instance = new JobInstance((long) i + 1, jobName);
            instances.add(instance);
            when(jobRepository.getJobExecutions(instance)).thenReturn(List.of(executions[i]));
        }
        when(jobRepository.getJobInstanceCount(jobName)).thenReturn((long) executions.length);
        when(jobRepository.getJobInstances(jobName, 0, 20)).thenReturn(instances);
    }

    private boolean shouldRun() {
        return new DatagouvJobRunDecider(jobRepository, jobName, false, false).shouldRun();
    }

    @Test
    void shouldRun_modeTest_retourneTrue() {
        DatagouvJobRunDecider decider = new DatagouvJobRunDecider(jobRepository, jobName, false, true);
        assertThat(decider.shouldRun()).isTrue();
    }

    @Test
    void shouldRun_forceRun_retourneTrue() {
        DatagouvJobRunDecider decider = new DatagouvJobRunDecider(jobRepository, jobName, true, false);
        assertThat(decider.shouldRun()).isTrue();
    }

    @Test
    void shouldRun_aucuneInstancePrecedente_retourneTrue() throws Exception {
        when(jobRepository.getJobInstanceCount(jobName)).thenReturn(0L);

        assertThat(shouldRun()).isTrue();
    }

    @Test
    void shouldRun_exceptionNoSuchJob_retourneTrue() throws Exception {
        when(jobRepository.getJobInstanceCount(jobName)).thenThrow(new NoSuchJobException("non trouvé"));

        assertThat(shouldRun()).isTrue();
    }

    @Test
    void shouldRun_publicationIlYAMoinsDe6Mois_retourneFalse() throws Exception {
        history(execution(BatchStatus.COMPLETED, LocalDateTime.now().minusMonths(2), PUBLICATION));

        assertThat(shouldRun()).isFalse();
    }

    @Test
    void shouldRun_publicationIlYAPlusDe6Mois_retourneTrue() throws Exception {
        history(execution(BatchStatus.COMPLETED, LocalDateTime.now().minusMonths(7), PUBLICATION));

        assertThat(shouldRun()).isTrue();
    }

    @Test
    void shouldRun_executionCompleteSansDateDeFin_estIgnoree() throws Exception {
        history(execution(BatchStatus.COMPLETED, null, PUBLICATION));

        assertThat(shouldRun()).isTrue();
    }

    @Test
    void shouldRun_executionEnEchecUniquement_retourneTrue() throws Exception {
        history(execution(BatchStatus.FAILED, LocalDateTime.now().minusDays(1), PUBLICATION));

        assertThat(shouldRun()).isTrue();
    }

    @Test
    void shouldRun_exportsRecentsEnModeTestOuSansEnvoi_neComptentPas() throws Exception {
        // Exports d'hier (mode test) et d'avant-hier (sans envoi) : la dernière publication date de 7 mois
        history(
                execution(BatchStatus.COMPLETED, LocalDateTime.now().minusDays(1), params("true", "true")),
                execution(BatchStatus.COMPLETED, LocalDateTime.now().minusDays(2), params("false", "false")),
                execution(BatchStatus.COMPLETED, LocalDateTime.now().minusMonths(7), PUBLICATION));

        assertThat(shouldRun()).isTrue();
    }

    @Test
    void shouldRun_exportSansEnvoiApresUnePublicationRecente_retourneFalse() throws Exception {
        history(
                execution(BatchStatus.COMPLETED, LocalDateTime.now().minusDays(1), params("false", "false")),
                execution(BatchStatus.COMPLETED, LocalDateTime.now().minusMonths(2), PUBLICATION));

        assertThat(shouldRun()).isFalse();
    }

    @Test
    void shouldRun_executionAnterieureSansParametres_compteCommePublication() throws Exception {
        history(execution(BatchStatus.COMPLETED, LocalDateTime.now().minusMonths(2), new JobParameters()));

        assertThat(shouldRun()).isFalse();
    }
}

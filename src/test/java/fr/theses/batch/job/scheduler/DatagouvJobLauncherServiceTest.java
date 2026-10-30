package fr.theses.batch.job.scheduler;

import fr.theses.batch.job.RunTimestamp;
import fr.theses.batch.job.listener.DatagouvOutputListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires de DatagouvJobLauncherService.
 */
class DatagouvJobLauncherServiceTest {

    private static final String JOB_NAME = "thesesDumpDataGouvJob";

    private JobOperator jobOperator;
    private Job job;
    private JobRepository jobRepository;
    private DatagouvJobLauncherService service;

    @BeforeEach
    void setUp() {
        jobOperator = mock(JobOperator.class);
        job = mock(Job.class);
        when(job.getName()).thenReturn(JOB_NAME);
        jobRepository = mock(JobRepository.class);
        service = new DatagouvJobLauncherService(jobOperator, job, jobRepository, new RunTimestamp("2026-01-02-03-04-05"),
                mock(DatagouvOutputListener.class));
        ReflectionTestUtils.setField(service, "thesesApiBaseUrl", "http://theses.test/api/v1/batch");
    }

    @Test
    void checkAndRunJob_urlApiThesesVide_refuseDeDemarrer() throws Exception {
        ReflectionTestUtils.setField(service, "thesesApiBaseUrl", "");
        ReflectionTestUtils.setField(service, "forceRun", true);

        assertThatThrownBy(() -> service.checkAndRunJob())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("THESES_API_BASE_URL");
        verify(jobOperator, never()).start(any(Job.class), any(JobParameters.class));
    }

    private void jobTermineAvec(BatchStatus status) throws Exception {
        JobExecution execution = mock(JobExecution.class);
        when(execution.getStatus()).thenReturn(status);
        when(jobOperator.start(any(Job.class), any(JobParameters.class))).thenReturn(execution);
    }

    @Test
    void checkAndRunJob_modeTest_lanceLeJobAvecLesParametresDuRun() throws Exception {
        ReflectionTestUtils.setField(service, "modeTest", true);
        jobTermineAvec(BatchStatus.COMPLETED);

        assertThat(service.checkAndRunJob()).isTrue();

        ArgumentCaptor<JobParameters> params = ArgumentCaptor.forClass(JobParameters.class);
        verify(jobOperator).start(eq(job), params.capture());
        assertThat(params.getValue().getString("runTimestamp")).isEqualTo("2026-01-02-03-04-05");
        assertThat(params.getValue().getLong("runTime")).isPositive();
        // Paramètres non identifiants lus par DatagouvJobRunDecider : ce run ne compte pas comme une publication
        assertThat(params.getValue().getString(DatagouvJobRunDecider.PARAM_MODE_TEST)).isEqualTo("true");
        assertThat(params.getValue().getString(DatagouvJobRunDecider.PARAM_UPLOAD_ENABLED)).isEqualTo("false");
        assertThat(params.getValue().getParameter(DatagouvJobRunDecider.PARAM_MODE_TEST).identifying()).isFalse();
    }

    @Test
    void checkAndRunJob_erreurAuLancement_propageLException() throws Exception {
        ReflectionTestUtils.setField(service, "modeTest", true);
        when(jobOperator.start(any(Job.class), any(JobParameters.class))).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> service.checkAndRunJob()).hasMessage("boom");
    }

    @Test
    void checkAndRunJob_jobEnEchec_leveUneException() throws Exception {
        ReflectionTestUtils.setField(service, "modeTest", true);
        jobTermineAvec(BatchStatus.FAILED);

        assertThatThrownBy(() -> service.checkAndRunJob())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FAILED");
    }

    @Test
    void checkAndRunJob_regleDes6MoisNonEchue_neLancePasLeJob() throws Exception {
        // Dernière exécution réussie hier : le job ne doit pas être relancé
        JobInstance instance = new JobInstance(1L, JOB_NAME);
        JobExecution execution = mock(JobExecution.class);
        when(execution.getStatus()).thenReturn(BatchStatus.COMPLETED);
        when(execution.getEndTime()).thenReturn(LocalDateTime.now().minusDays(1));
        when(execution.getJobParameters()).thenReturn(new JobParameters());
        when(jobRepository.getJobInstanceCount(JOB_NAME)).thenReturn(1L);
        when(jobRepository.getJobInstances(JOB_NAME, 0, DatagouvJobRunDecider.PAGE_SIZE)).thenReturn(List.of(instance));
        when(jobRepository.getJobExecutions(instance)).thenReturn(List.of(execution));

        assertThat(service.checkAndRunJob()).isFalse();
        verify(jobOperator, never()).start(any(Job.class), any(JobParameters.class));
    }
}

package fr.theses.batch;

import fr.theses.batch.job.datagouv.scheduler.DatagouvJobLauncherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires du répartiteur ThesesBatchRunner.
 */
class ThesesBatchRunnerTest {

    private JobOperator jobOperator;
    private Job anrJob;
    private DatagouvJobLauncherService datagouvJobLauncherService;
    private ThesesBatchRunner runner;

    @BeforeEach
    void setUp() {
        jobOperator = mock(JobOperator.class);
        anrJob = mock(Job.class);
        datagouvJobLauncherService = mock(DatagouvJobLauncherService.class);
        runner = new ThesesBatchRunner(jobOperator, anrJob, datagouvJobLauncherService);
    }

    private void anrTermineAvec(BatchStatus status) throws Exception {
        JobExecution execution = mock(JobExecution.class);
        when(execution.getStatus()).thenReturn(status);
        when(jobOperator.start(eq(anrJob), any(JobParameters.class))).thenReturn(execution);
    }

    @Test
    void run_sansArguments_neDeclencheAucunJob() throws Exception {
        runner.run();

        verify(jobOperator, never()).start(any(Job.class), any(JobParameters.class));
        verify(datagouvJobLauncherService, never()).checkAndRunJob();
    }

    @Test
    void run_avecArgumentAnr_lanceAnrJob() throws Exception {
        anrTermineAvec(BatchStatus.COMPLETED);

        runner.run("anr");

        verify(jobOperator).start(eq(anrJob), any(JobParameters.class));
        verify(datagouvJobLauncherService, never()).checkAndRunJob();
    }

    @Test
    void run_anrEnEchec_leveUneException() throws Exception {
        anrTermineAvec(BatchStatus.FAILED);

        assertThatThrownBy(() -> runner.run("anr"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FAILED");
    }

    @Test
    void run_avecProprieteBatchJobDatagouv_lanceDatagouv() throws Exception {
        // --batch.job=datagouv (theses-batch.sh) est converti par Spring en propriété batch.job
        ReflectionTestUtils.setField(runner, "configuredJob", "datagouv");

        runner.run("--batch.job=datagouv");

        verify(datagouvJobLauncherService).checkAndRunJob();
        verify(jobOperator, never()).start(eq(anrJob), any(JobParameters.class));
    }

    @Test
    void run_argumentSeulPrioritaireSurLaPropriete() throws Exception {
        ReflectionTestUtils.setField(runner, "configuredJob", "anr");

        runner.run("datagouv");

        verify(datagouvJobLauncherService).checkAndRunJob();
        verify(jobOperator, never()).start(eq(anrJob), any(JobParameters.class));
    }

    @Test
    void run_avecArgumentDump_lanceDatagouv() throws Exception {
        runner.run("DUMP");

        verify(datagouvJobLauncherService).checkAndRunJob();
    }

    @Test
    void run_avecJobConfigure_lanceCeJob() throws Exception {
        ReflectionTestUtils.setField(runner, "configuredJob", "anr");
        anrTermineAvec(BatchStatus.COMPLETED);

        // Les arguments sans rapport (surcharges Spring) sont ignorés
        runner.run("--app.anr.max-files=50");

        verify(jobOperator).start(eq(anrJob), any(JobParameters.class));
    }

    @Test
    void run_avecJobVide_neDeclencheAucunJob() throws Exception {
        ReflectionTestUtils.setField(runner, "configuredJob", "");

        runner.run("--batch.job=");

        verify(jobOperator, never()).start(any(Job.class), any(JobParameters.class));
        verify(datagouvJobLauncherService, never()).checkAndRunJob();
    }

    @Test
    void run_avecJobInconnu_leveIllegalArgumentException() {
        ReflectionTestUtils.setField(runner, "configuredJob", "inconnu");

        assertThatThrownBy(() -> runner.run("--batch.job=inconnu"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Job inconnu");
    }
}

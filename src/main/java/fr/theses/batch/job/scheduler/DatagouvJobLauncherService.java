package fr.theses.batch.job.scheduler;

import fr.theses.batch.job.RunTimestamp;
import fr.theses.batch.job.listener.DatagouvOutputListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Service chargé d'orchestrer la vérification des conditions de lancement
 * et de déclencher l'exécution effective du Job Spring Batch thesesDumpDataGouvJob.
 */
@Slf4j
@Service
public class DatagouvJobLauncherService {

    private final JobOperator jobOperator;
    private final Job thesesDumpDataGouvJob;
    private final JobRepository jobRepository;
    private final RunTimestamp runTimestamp;
    private final DatagouvOutputListener outputListener;

    @Value("${app.datagouv.force-run:false}")
    private boolean forceRun;

    @Value("${app.datagouv.mode-test:false}")
    private boolean modeTest;

    @Value("${app.datagouv.upload-enabled:false}")
    private boolean uploadEnabled;

    @Value("${app.theses.api-base-url:}")
    private String thesesApiBaseUrl;

    /**
     * Constructeur injectant les composants Spring Batch requis.
     *
     * @param jobOperator            Composant de lancement du Job.
     * @param thesesDumpDataGouvJob   Job Spring Batch Datagouv.
     * @param jobRepository            Explorateur de l'historique des jobs.
     * @param runTimestamp           Horodatage unique du run.
     * @param outputListener         Rangement des fichiers du job et purge des anciens logs.
     */
    public DatagouvJobLauncherService(JobOperator jobOperator,
                                      @Qualifier("thesesDumpDataGouvJob") Job thesesDumpDataGouvJob,
                                      JobRepository jobRepository,
                                      RunTimestamp runTimestamp,
                                      DatagouvOutputListener outputListener) {
        this.jobOperator = jobOperator;
        this.thesesDumpDataGouvJob = thesesDumpDataGouvJob;
        this.jobRepository = jobRepository;
        this.runTimestamp = runTimestamp;
        this.outputListener = outputListener;
    }

    /**
     * Évalue la règle métier (6 mois) et lance le job si nécessaire.
     *
     * @return true si le job s'est lancé et terminé avec succès, false s'il a été ignoré.
     * @throws Exception si le lancement échoue.
     * @throws IllegalStateException si l'URL de l'API theses.fr n'est pas configurée, ou si le job se
     *                               termine avec un autre statut que COMPLETED.
     */
    public boolean checkAndRunJob() throws Exception {
        // Pas d'URL par défaut : sans elle, on refuse de démarrer plutôt que d'exporter la mauvaise source
        if (thesesApiBaseUrl == null || thesesApiBaseUrl.isBlank()) {
            throw new IllegalStateException("THESES_API_BASE_URL (app.theses.api-base-url) n'est pas définie : "
                    + "la tâche thesesDumpDataGouvJob ne peut pas démarrer.");
        }
        // Chaque passage du cron crée un log, même quand le job est écarté : purge avant la décision
        outputListener.purgeOldLogs();
        log.info("Vérification des conditions d'exécution de la tâche thesesDumpDataGouvJob...");

        DatagouvJobRunDecider decider = new DatagouvJobRunDecider(jobRepository, thesesDumpDataGouvJob.getName(), forceRun, modeTest);

        if (decider.shouldRun()) {
            log.info("Conditions réunies. Démarrage de la tâche thesesDumpDataGouvJob...");
            JobParameters params = new JobParametersBuilder()
                    .addLong("runTime", System.currentTimeMillis())
                    .addString("runTimestamp", runTimestamp.value())
                    // Non identifiants : servent à DatagouvJobRunDecider pour ne compter que les vraies publications
                    .addString(DatagouvJobRunDecider.PARAM_MODE_TEST, String.valueOf(modeTest), false)
                    .addString(DatagouvJobRunDecider.PARAM_UPLOAD_ENABLED, String.valueOf(uploadEnabled), false)
                    .toJobParameters();

            JobExecution execution = jobOperator.start(thesesDumpDataGouvJob, params);
            if (execution.getStatus() != BatchStatus.COMPLETED) {
                throw new IllegalStateException("La tâche thesesDumpDataGouvJob s'est terminée avec le statut "
                        + execution.getStatus());
            }
            log.info("Exécution de la tâche thesesDumpDataGouvJob terminée avec succès.");
            return true;
        } else {
            log.info("La tâche thesesDumpDataGouvJob a été ignorée (Règle des 6 mois non échue).");
            return false;
        }
    }
}

package fr.theses.batch;

import fr.theses.batch.job.datagouv.scheduler.DatagouvJobLauncherService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Point d'entrée CommandLineRunner de l'application theses-batch.
 * Permet d'exécuter soit le batch ANR, soit le batch Datagouv en fonction de la commande passée en argument.
 */
@Slf4j
@Component
public class ThesesBatchRunner implements CommandLineRunner {

    private final JobOperator jobOperator;
    private final Job anrJob;
    private final DatagouvJobLauncherService datagouvJobLauncherService;

    // Propriété batch.job : --batch.job=datagouv (theses-batch.sh), variable BATCH_JOB ou configuration
    @Value("${batch.job:}")
    private String configuredJob;

    /**
     * Constructeur injectant les jobs et services nécessaires.
     */
    public ThesesBatchRunner(JobOperator jobOperator,
                             @Qualifier("anrJob") Job anrJob,
                             DatagouvJobLauncherService datagouvJobLauncherService) {
        this.jobOperator = jobOperator;
        this.anrJob = anrJob;
        this.datagouvJobLauncherService = datagouvJobLauncherService;
    }

    /**
     * Exécute le batch correspondant à l'argument ou à la propriété spécifiée.
     *
     * @param args Arguments passés en ligne de commande.
     * @throws Exception en cas d'erreur critique d'exécution.
     */
    @Override
    public void run(String... args) throws Exception {
        String jobToRun = resolveJobToRun(args);

        if (jobToRun == null || jobToRun.isBlank()) {
            log.info("theses-batch prêt. Aucun job spécifié (passer 'anr' ou 'datagouv' en argument).");
            return;
        }

        switch (jobToRun.toLowerCase()) {
            case "anr" -> runAnr();
            case "datagouv", "dump" -> runDatagouv();
            default -> {
                log.error("Job inconnu : '{}'. Valeurs acceptées : 'anr', 'datagouv'.", jobToRun);
                throw new IllegalArgumentException("Job inconnu : " + jobToRun);
            }
        }
    }

    /**
     * Détermine quel batch exécuter : un argument seul (anr, datagouv, dump), sinon la propriété batch.job.
     *
     * @param args Arguments CLI.
     * @return Nom du job, ou null si non spécifié.
     */
    private String resolveJobToRun(String[] args) {
        for (String arg : args) {
            if ("anr".equalsIgnoreCase(arg) || "datagouv".equalsIgnoreCase(arg) || "dump".equalsIgnoreCase(arg)) {
                return arg;
            }
        }
        return configuredJob;
    }

    /**
     * Exécute le batch ANR.
     */
    private void runAnr() throws Exception {
        log.info("Démarrage du batch ANR (anrJob)...");
        JobParameters params = new JobParametersBuilder()
                .addLong("runTime", System.currentTimeMillis())
                .toJobParameters();
        JobExecution execution = jobOperator.start(anrJob, params);
        if (execution.getStatus() != BatchStatus.COMPLETED) {
            throw new IllegalStateException("Le batch ANR s'est terminé avec le statut " + execution.getStatus());
        }
        log.info("Batch ANR terminé.");
    }

    /**
     * Exécute le batch Datagouv (avec évaluation de la règle des 6 mois).
     */
    private void runDatagouv() throws Exception {
        log.info("Démarrage du batch Datagouv (thesesDumpDataGouvJob)...");
        datagouvJobLauncherService.checkAndRunJob();
        log.info("Batch Datagouv terminé.");
    }
}

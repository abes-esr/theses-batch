package fr.theses.batch.job.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.NoSuchJobException;
import org.springframework.batch.core.repository.JobRepository;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * Classe chargée de décider si le Job du Batch datagouv doit être lancé.
 * Implémente la règle métier d'exécution tous les 6 mois depuis la dernière publication réussie
 * (hors mode test et exports sans téléversement)
 * et gère également l'option de forçage manuel (FORCE_RUN) et le mode test.
 */
@Slf4j
public class DatagouvJobRunDecider {

    /** Paramètre d'exécution : "true" si l'export a été fait en mode test. */
    public static final String PARAM_MODE_TEST = "modeTest";
    /** Paramètre d'exécution : "false" si le téléversement vers data.gouv.fr était désactivé. */
    public static final String PARAM_UPLOAD_ENABLED = "uploadEnabled";
    /** Nombre d'instances lues par requête lors du parcours de l'historique. */
    static final int PAGE_SIZE = 100;

    private final JobRepository jobRepository;
    private final String jobName;
    private final boolean forceRun;
    private final boolean modeTest;

    /**
     * Constructeur initialisant les dépendances pour la décision.
     *
     * @param jobRepository Explorateur de métadonnées du Batch.
     * @param jobName     Nom du job à inspecter.
     * @param forceRun    Vrai si le lancement immédiat est forcé.
     * @param modeTest    Vrai si le mode test est activé.
     */
    public DatagouvJobRunDecider(JobRepository jobRepository, String jobName, boolean forceRun, boolean modeTest) {
        this.jobRepository = jobRepository;
        this.jobName = jobName;
        this.forceRun = forceRun;
        this.modeTest = modeTest;
    }

    /**
     * Analyse les exécutions passées pour décider si le job doit être exécuté.
     *
     * @return true si le job doit être exécuté, false sinon.
     */
    public boolean shouldRun() {
        // 1. Vérification du mode test ou de l'option de forçage
        if (modeTest) {
            log.info("Mode test actif. Le contrôle de la règle des 6 mois est ignoré.");
            return true;
        }
        if (forceRun) {
            // Un FORCE_RUN laissé dans l'environnement du conteneur republie à chaque passage du cron
            log.warn("Forçage d'exécution actif (app.datagouv.force-run ou FORCE_RUN) : la règle des 6 mois est ignorée. "
                    + "S'il est défini dans l'environnement du conteneur, chaque passage du cron relancera l'export.");
            return true;
        }

        try {
            // 2. Récupération du nombre d'instances existantes pour ce Job
            long jobInstanceCount = jobRepository.getJobInstanceCount(jobName);
            if (jobInstanceCount == 0) {
                log.info("Aucune exécution précédente détectée pour le job '{}'. Lancement requis.", jobName);
                return true;
            }

            // 3. Parcours de tout l'historique, du plus récent au plus ancien, jusqu'à la dernière publication
            return checkPastExecutions();
        } catch (NoSuchJobException e) {
            log.info("Aucune exécution précédente (job non trouvé) détectée pour le job '{}'. Lancement requis.", jobName);
            return true;
        }
    }

    /**
     * Parcourt l'historique complet du job, page par page et de la plus récente à la plus ancienne
     * instance, à la recherche de la dernière publication réussie.
     *
     * @return true si le job doit être exécuté, false sinon.
     */
    private boolean checkPastExecutions() {
        for (int start = 0; ; start += PAGE_SIZE) {
            List<JobInstance> jobInstances = jobRepository.getJobInstances(jobName, start, PAGE_SIZE);
            for (JobInstance instance : jobInstances) {
                for (JobExecution execution : jobRepository.getJobExecutions(instance)) {
                    Optional<Boolean> decision = checkExecutionStatus(execution);
                    if (decision.isPresent()) {
                        return decision.get();
                    }
                }
            }
            if (jobInstances.size() < PAGE_SIZE) {
                break;
            }
        }
        // 4. Aucune publication réussie dans tout l'historique (échecs, abandons, exports de test) : on lance le Job.
        log.info("Aucune publication réussie dans l'historique du job '{}'. Lancement requis.", jobName);
        return true;
    }

    /**
     * Indique si l'exécution a réellement publié sur data.gouv.fr : seules celles-ci comptent pour la règle
     * des 6 mois. Un export en mode test ou sans téléversement n'empêche pas la publication suivante.
     * Les exécutions antérieures à ces paramètres (absents) sont considérées comme des publications.
     */
    private static boolean isPublication(JobExecution execution) {
        JobParameters params = execution.getJobParameters();
        return !"true".equals(params.getString(PARAM_MODE_TEST))
                && !"false".equals(params.getString(PARAM_UPLOAD_ENABLED));
    }

    /**
     * Évalue si une exécution spécifique s'est terminée avec succès et calcule la décision de planification.
     *
     * @param execution L'exécution de job à évaluer.
     * @return Boolean.TRUE si le job doit s'exécuter, Boolean.FALSE si l'exécution est récente, null si non COMPLETED.
     */
    private Optional<Boolean> checkExecutionStatus(JobExecution execution) {
        if (execution.getStatus() == BatchStatus.COMPLETED && isPublication(execution)) {
            LocalDateTime endTime = execution.getEndTime();
            if (endTime != null) {
                log.info("Dernière exécution réussie détectée le : {}.", endTime);
                LocalDateTime sixMonthsAgo = LocalDateTime.now(ZoneId.systemDefault()).minusMonths(6);

                if (endTime.isBefore(sixMonthsAgo)) {
                    log.info("La dernière exécution réussie remonte à plus de 6 mois. Exécution requise.");
                    return Optional.of(Boolean.TRUE);
                } else {
                    log.info("La dernière exécution réussie remonte à moins de 6 mois. Exécution non requise.");
                    return Optional.of(Boolean.FALSE);
                }
            }
        }
        return Optional.empty();
    }
}

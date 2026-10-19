package fr.theses.batch.job.configuration;

import fr.theses.batch.business.anr.model.dto.ANRMatchDTO;
import fr.theses.batch.job.processor.ANRProcessor;
import fr.theses.batch.job.processor.ANRSolrEnricherProcessor;
import fr.theses.batch.job.reader.ANRReader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.ChunkOrientedStepBuilder;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.support.CompositeItemProcessor;
import org.springframework.batch.infrastructure.item.support.CompositeItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

/**
 * Configuration du job ANR
 */
@Slf4j
@Configuration
public class ANRBatchConfiguration {

    private final int chunkSize;
    private final int processThreads;

    public ANRBatchConfiguration(
            @Value("${app.anr.chunk-size:100}") int chunkSize,
            @Value("${app.anr.process-threads:8}") int processThreads) {
        this.chunkSize = chunkSize;
        this.processThreads = processThreads;
    }

    /**
     * Crée un processor composite qui chain ANRProcessor et ANRSolrEnricherProcessor
     */
    @Bean
    public CompositeItemProcessor<ANRMatchDTO, ANRMatchDTO> anrCompositeProcessor(
            ANRProcessor anrProcessor,
            ANRSolrEnricherProcessor anrSolrEnricherProcessor) {
        CompositeItemProcessor<ANRMatchDTO, ANRMatchDTO> compositeProcessor = new CompositeItemProcessor<>();
        compositeProcessor.setDelegates(List.of(anrProcessor, anrSolrEnricherProcessor));
        return compositeProcessor;
    }

    /**
     * Threads qui traitent les PDF d'un chunk en parallèle (app.anr.process-threads).
     * Threads démons : le contexte Spring n'est pas fermé en fin de job, et des threads ordinaires
     * empêcheraient la JVM de s'arrêter une fois le job terminé.
     **/
    @Bean
    public ThreadPoolTaskExecutor anrProcessExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, processThreads));
        executor.setMaxPoolSize(Math.max(1, processThreads));
        executor.setThreadNamePrefix("anr-");
        executor.setDaemon(true);
        return executor;
    }

    /**
     * Crée le step ANR
     * Sans transactionManager explicite, Spring Batch utilise un ResourcelessTransactionManager :
     * aucune ressource transactionnelle n'est en jeu, les writers n'écrivent que des fichiers.
     * Au-delà d'un thread, les items d'un chunk sont traités en parallèle : la lecture et l'écriture
     * restent sur le thread du step, et les items sont écrits dans l'ordre de lecture.
     * Utilise le PlatformTransactionManager fourni par Spring Boot (via DataSourceConfig)
     * pour permettre la persistance des métadonnées Batch via le JobRepository.
     */
    @Bean
    public Step anrStep(JobRepository jobRepository,
                        ANRReader anrReader,
                        CompositeItemProcessor<ANRMatchDTO, ANRMatchDTO> anrCompositeProcessor,
                        CompositeItemWriter<ANRMatchDTO> anrCompositeWriter,
                        ThreadPoolTaskExecutor anrProcessExecutor,
                        PlatformTransactionManager transactionManager) {
        ChunkOrientedStepBuilder<ANRMatchDTO, ANRMatchDTO> builder = new StepBuilder("anrStep", jobRepository)
                .<ANRMatchDTO, ANRMatchDTO>chunk(chunkSize)
                .transactionManager(transactionManager)
                .reader(anrReader)
                .processor(anrCompositeProcessor)
                .writer(anrCompositeWriter);
        if (processThreads > 1) {
            builder.taskExecutor(anrProcessExecutor);
        }
        log.info("Step ANR : chunks de {} fichiers, {} thread(s) de traitement", chunkSize, Math.max(1, processThreads));
        return builder.build();
    }

    /**
     * Crée le job ANR
     */
    @Bean
    public Job anrJob(JobRepository jobRepository,
                      Step anrStep) {
        return new JobBuilder("anrJob", jobRepository)
                .start(anrStep)
                .build();
    }
}

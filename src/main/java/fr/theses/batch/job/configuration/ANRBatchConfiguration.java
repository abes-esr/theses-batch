package fr.theses.batch.job.configuration;

import fr.theses.batch.business.anr.model.dto.ANRMatchDTO;
import fr.theses.batch.job.processor.ANRProcessor;
import fr.theses.batch.job.processor.ANRSolrEnricherProcessor;
import fr.theses.batch.job.reader.ANRReader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.listener.SkipListener;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.skip.SkipPolicy;
import org.springframework.batch.infrastructure.item.support.CompositeItemProcessor;
import org.springframework.batch.infrastructure.item.support.CompositeItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.util.List;

/**
 * Configuration du job ANR
 */
@Configuration
@Slf4j
public class ANRBatchConfiguration {
    
    private final int chunkSize;
    
    public ANRBatchConfiguration(
            @Value("${app.anr.chunk-size:100}") int chunkSize) {
        this.chunkSize = chunkSize;
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
     * Crée la politique de skip pour les exceptions de données (PDF malformé)
     */
    private SkipPolicy anrSkipPolicy() {
        return (exception, skipCount) -> {
            if ((exception instanceof IllegalStateException) || (exception instanceof IOException)) {
                log.warn("Skip de l'item {} : {} (PDF malformé)", 
                       exception != null ? exception.toString() : "unknown", exception.getMessage());
                return true;
            }
            return false;
        };
    }

    /**
     * Crée le listener pour journaliser les skips
     */
    private SkipListener<ANRMatchDTO, ANRMatchDTO> anrSkipListener() {
        return new SkipListener<ANRMatchDTO, ANRMatchDTO>() {
            @Override
            public void onSkipInRead(Throwable t) {
                log.warn("Skip en lecture : {}", t.getMessage());
            }
            
            @Override
            public void onSkipInProcess(ANRMatchDTO item, Throwable t) {
                log.warn("Skip en traitement du fichier {} : {} (PDF malformé)", 
                       item != null ? item.filePath() : "unknown", t.getMessage());
            }
            
            @Override
            public void onSkipInWrite(ANRMatchDTO item, Throwable t) {
                log.warn("Skip en écriture du fichier {} : {}", 
                       item != null ? item.filePath() : "unknown", t.getMessage());
            }
        };
    }

    /**
     * Crée le step ANR
     * Sans transactionManager explicite, Spring Batch utilise un ResourcelessTransactionManager :
     * aucune ressource transactionnelle n'est en jeu, les writers n'écrivent que des fichiers.
     * <p>
     * Filet de sécurité : skip des exceptions de données (PDF malformé) avec une limite élevée
     * pour éviter qu'un seul PDF corrompu ne fasse échouer tout le batch.
     */
    @Bean
    public Step anrStep(JobRepository jobRepository,
                        ANRReader anrReader,
                        CompositeItemProcessor<ANRMatchDTO, ANRMatchDTO> anrCompositeProcessor,
                        CompositeItemWriter<ANRMatchDTO> anrCompositeWriter) {
        return new StepBuilder("anrStep", jobRepository)
                .<ANRMatchDTO, ANRMatchDTO>chunk(chunkSize)
                .reader(anrReader)
                .processor(anrCompositeProcessor)
                .writer(anrCompositeWriter)
                .faultTolerant()
                .skipPolicy(anrSkipPolicy())
                .skipLimit(1000)
                .listener(anrSkipListener())
                .build();
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

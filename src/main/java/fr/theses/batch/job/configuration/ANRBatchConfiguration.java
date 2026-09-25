package fr.theses.batch.job.configuration;

import fr.theses.batch.business.anr.model.dto.ANRMatchDTO;
import fr.theses.batch.job.processor.ANRProcessor;
import fr.theses.batch.job.processor.ANRSolrEnricherProcessor;
import fr.theses.batch.job.reader.ANRReader;
import fr.theses.batch.job.writer.ANRWriter;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.support.CompositeItemProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

/**
 * Configuration du job ANR
 */
@Configuration
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
     * Crée le step ANR
     */
    @Bean
    public Step anrStep(JobRepository jobRepository,
                        PlatformTransactionManager transactionManager,
                        ANRReader anrReader,
                        CompositeItemProcessor<ANRMatchDTO, ANRMatchDTO> anrCompositeProcessor,
                        ANRWriter anrWriter) {
        return new StepBuilder("anrStep", jobRepository)
                .<ANRMatchDTO, ANRMatchDTO>chunk(chunkSize)
                .transactionManager(transactionManager)
                .reader(anrReader)
                .processor(anrCompositeProcessor)
                .writer(anrWriter)
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

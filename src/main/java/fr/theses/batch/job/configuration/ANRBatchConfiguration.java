package fr.theses.batch.job.configuration;

import fr.theses.batch.business.anr.model.dto.ANRMatchDTO;
import fr.theses.batch.job.processor.ANRProcessor;
import fr.theses.batch.job.processor.ANRSolrEnricherProcessor;
import fr.theses.batch.job.reader.ANRReader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.parameters.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.support.CompositeItemProcessor;
import org.springframework.batch.infrastructure.item.support.CompositeItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
     * Crée le step ANR.
     * Sans transactionManager explicite, Spring Batch utilise un
     * ResourcelessTransactionManager :
     * aucune ressource transactionnelle n'est en jeu, les writers n'écrivent que
     * des fichiers.
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
                .build();
    }

    /**
     * Crée le job ANR.
     * L'incrémenteur RunIdIncrementer attribue un paramètre unique (run.id) à
     * chaque exécution,
     * évitant l'erreur JobInstanceAlreadyCompleteException lors des lancements
     * successifs
     * sur la base de données H2 persistante.
     */
    @Bean
    public Job anrJob(JobRepository jobRepository,
                      Step anrStep) {
        return new JobBuilder("anrJob", jobRepository)
                .incrementer(new RunIdIncrementer())
                .start(anrStep)
                .build();
    }
}

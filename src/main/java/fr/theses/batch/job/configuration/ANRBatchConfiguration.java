package fr.theses.batch.job.configuration;

import fr.theses.batch.business.anr.model.dto.ANRMatchDTO;
import fr.theses.batch.business.anr.service.ANRSearchService;
import fr.theses.batch.job.processor.ANRProcessor;
import fr.theses.batch.job.processor.PDFProcessingService;
import fr.theses.batch.job.reader.ANRReader;
import fr.theses.batch.job.writer.ANRWriter;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Configuration du job ANR
 */
@Configuration
public class ANRBatchConfiguration {
    
    private final int chunkSize;
    
    public ANRBatchConfiguration(
            @Value("${app.chunk-size:100}") int chunkSize) {
        this.chunkSize = chunkSize;
    }
    
    /**
     * Crée le processor ANR
     */
    @Bean
    public ANRProcessor anrProcessor(ANRSearchService anrSearchService,
                                     PDFProcessingService pdfProcessingService,
                                     @Value("${app.nb-pages:0}") int maxPages,
                                     @Value("${app.anr.pattern}") String anrPattern) {
        return new ANRProcessor(anrSearchService, pdfProcessingService, maxPages, anrPattern);
    }
    
    /**
     * Crée le step ANR
     */
    @Bean
    public Step anrStep(JobRepository jobRepository,
                        PlatformTransactionManager transactionManager,
                        ANRReader anrReader,
                        ANRProcessor anrProcessor,
                        ANRWriter anrWriter) {
        return new StepBuilder("anrStep", jobRepository)
                .<ANRMatchDTO, ANRMatchDTO>chunk(chunkSize, transactionManager)
                .reader(anrReader)
                .processor(anrProcessor)
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

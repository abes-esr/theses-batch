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
import org.springframework.batch.item.support.SynchronizedItemStreamReader;
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
    private final int maxWorkers;
    
    public ANRBatchConfiguration(
            @Value("${app.chunk-size:100}") int chunkSize,
            @Value("${app.max-workers:2}") int maxWorkers) {
        this.chunkSize = chunkSize;
        this.maxWorkers = maxWorkers;
    }
    
    /**
     * Crée le reader ANR avec synchronisation pour la lecture multi-thread
     */
    @Bean
    public SynchronizedItemStreamReader<ANRMatchDTO> anrSynchronizedReader(ANRReader anrReader) {
        SynchronizedItemStreamReader<ANRMatchDTO> reader = new SynchronizedItemStreamReader<>();
        reader.setDelegate(anrReader);
        return reader;
    }
    
    /**
     * Crée le processor ANR
     */
    @Bean
    public ANRProcessor anrProcessor(ANRSearchService anrSearchService,
                                     PDFProcessingService pdfProcessingService,
                                     @Value("${app.nb-pages:0}") int maxPages) {
        return new ANRProcessor(anrSearchService, pdfProcessingService, maxPages);
    }
    
    /**
     * Crée le step ANR
     */
    @Bean
    public Step anrStep(JobRepository jobRepository,
                        PlatformTransactionManager transactionManager,
                        SynchronizedItemStreamReader<ANRMatchDTO> anrSynchronizedReader,
                        ANRProcessor anrProcessor,
                        ANRWriter anrWriter) {
        return new StepBuilder("anrStep", jobRepository)
                .<ANRMatchDTO, ANRMatchDTO>chunk(chunkSize, transactionManager)
                .reader(anrSynchronizedReader)
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

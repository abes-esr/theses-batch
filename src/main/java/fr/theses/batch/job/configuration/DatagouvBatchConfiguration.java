package fr.theses.batch.job.configuration;

import fr.theses.batch.business.datagouv.model.ExportThesis;
import fr.theses.batch.business.datagouv.model.Thesis;
import fr.theses.batch.job.RunTimestamp;
import fr.theses.batch.job.listener.DatagouvOutputListener;
import fr.theses.batch.job.processor.DatagouvThesisProcessor;
import fr.theses.batch.job.reader.DatagouvThesesItemReader;
import fr.theses.batch.job.tasklet.DatagouvCsvTasklet;
import fr.theses.batch.job.tasklet.DatagouvGzipTasklet;
import fr.theses.batch.job.tasklet.DatagouvUploadTasklet;
import fr.theses.batch.job.writer.json.DatagouvJsonlLineAggregator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.parameters.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.batch.infrastructure.item.file.FlatFileItemWriter;
import org.springframework.batch.infrastructure.item.file.builder.FlatFileItemWriterBuilder;
import org.springframework.batch.infrastructure.item.json.JacksonJsonObjectMarshaller;
import org.springframework.batch.infrastructure.item.json.JsonFileItemWriter;
import org.springframework.batch.infrastructure.item.json.builder.JsonFileItemWriterBuilder;
import org.springframework.batch.infrastructure.item.support.CompositeItemWriter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.RestClient;

import java.io.File;
import java.time.Duration;
import java.util.List;

/**
 * Configuration globale de la tâche (Job) Spring Batch thesesDumpDataGouvJob pour data.gouv.fr.
 * Définit les RestClients, Readers, Writers, Tasklet, Steps et le Job principal.
 */
@Slf4j
@Configuration
public class DatagouvBatchConfiguration {

    /**
     * RestClient configuré pour requêter l'API theses.fr.
     */
    @Bean
    public RestClient thesesRestClient(
            @Value("${app.theses.api-base-url:}") String apiBaseUrl,
            @Value("${app.theses.connect-timeout:30s}") Duration connectTimeout,
            @Value("${app.theses.read-timeout:60s}") Duration readTimeout) {
        log.info("Initialisation du RestClient de theses.fr avec l'URL de base : {} (connectTimeout: {}, readTimeout: {})",
                apiBaseUrl, connectTimeout, readTimeout);
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);

        return RestClient.builder()
                .baseUrl(apiBaseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    /**
     * RestClient pour le téléversement multipart vers data.gouv.fr.
     * Le corps est mis en tampon afin d'envoyer un en-tête Content-Length : sans lui, la requête
     * part en streaming (chunked) et data.gouv.fr ne reçoit pas le paramètre "file".
     * DatagouvUploadTasklet découpe les gros fichiers : le tampon ne dépasse pas la taille d'un morceau.
     * Timeouts obligatoires : sans eux, un serveur muet bloquerait le batch et son verrou indéfiniment,
     * et les crons suivants ne pourraient plus retenter la publication.
     */
    @Bean
    public RestClient dataGouvRestClient(
            @Value("${app.datagouv.connect-timeout:30s}") Duration connectTimeout,
            @Value("${app.datagouv.read-timeout:5m}") Duration readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        return RestClient.builder()
                .requestFactory(new BufferingClientHttpRequestFactory(requestFactory))
                .build();
    }

    /**
     * Reader récupérant les thèses soutenues de façon paginée depuis theses.fr.
     */
    @Bean
    public DatagouvThesesItemReader thesesReader(
            RestClient thesesRestClient,
            @Value("${app.theses.page-size:1000}") int pageSize,
            @Value("${app.datagouv.mode-test:false}") boolean modeTest) {
        return new DatagouvThesesItemReader(thesesRestClient, pageSize, modeTest);
    }

    /**
     * Processeur nettoyant et harmonisant les données des thèses avant écriture.
     */
    @Bean
    public DatagouvThesisProcessor thesisProcessor() {
        return new DatagouvThesisProcessor();
    }

    /**
     * Writer écrivant les thèses au format JSONL (une thèse par ligne en JSON).
     */
    @Bean
    @StepScope
    public FlatFileItemWriter<ExportThesis> datagouvJsonlWriter(
            @Value("${app.output-dir:output}") String outputDir,
            RunTimestamp runTimestamp,
            JsonMapper jsonMapper) {
        return new FlatFileItemWriterBuilder<ExportThesis>()
                .name("datagouvJsonlWriter")
                .resource(new FileSystemResource(new File(DatagouvOutputListener.tmpDir(outputDir), runTimestamp.value() + "_datagouv_results.jsonl")))
                .encoding("UTF-8")
                .lineAggregator(new DatagouvJsonlLineAggregator(jsonMapper))
                .build();
    }

    /**
     * Writer écrivant les thèses au format JSON tableau standard.
     * Même JsonMapper que le JSONL : une thèse est sérialisée à l'identique dans les deux fichiers.
     */
    @Bean
    @StepScope
    public JsonFileItemWriter<ExportThesis> datagouvJsonWriter(
            @Value("${app.output-dir:output}") String outputDir,
            RunTimestamp runTimestamp,
            JsonMapper jsonMapper) {
        return new JsonFileItemWriterBuilder<ExportThesis>()
                .name("datagouvJsonWriter")
                .resource(new FileSystemResource(new File(DatagouvOutputListener.tmpDir(outputDir), runTimestamp.value() + "_datagouv_results.json")))
                .jsonObjectMarshaller(new JacksonJsonObjectMarshaller<>(jsonMapper))
                .build();
    }

    /**
     * Writer composite distribuant chaque thèse lue aux 2 formats simultanés (JSONL, JSON).
     * Le CSV est généré ensuite à partir du JSONL (csvStep).
     */
    @Bean
    @StepScope
    public CompositeItemWriter<ExportThesis> datagouvCompositeWriter(
            @Qualifier("datagouvJsonlWriter") FlatFileItemWriter<ExportThesis> datagouvJsonlWriter,
            @Qualifier("datagouvJsonWriter") JsonFileItemWriter<ExportThesis> datagouvJsonWriter) {
        CompositeItemWriter<ExportThesis> writer = new CompositeItemWriter<>();
        writer.setDelegates(List.of(datagouvJsonlWriter, datagouvJsonWriter));
        return writer;
    }

    /**
     * Étape 1 : Lecture paginée de l'API et écriture simultanée du JSONL et du JSON.
     */
    @Bean
    public Step fetchAndWriteStep(JobRepository jobRepository,
                                  PlatformTransactionManager transactionManager,
                                  DatagouvThesesItemReader thesesReader,
                                  DatagouvThesisProcessor thesisProcessor,
                                  @Qualifier("datagouvCompositeWriter") CompositeItemWriter<ExportThesis> datagouvCompositeWriter,
                                  @Qualifier("datagouvJsonlWriter") FlatFileItemWriter<ExportThesis> datagouvJsonlWriter,
                                  @Qualifier("datagouvJsonWriter") JsonFileItemWriter<ExportThesis> datagouvJsonWriter,
                                  @Value("${app.datagouv.chunk-size:100}") int chunkSize) {
        return new StepBuilder("fetchAndWriteStep", jobRepository)
                .<Thesis, ExportThesis>chunk(chunkSize)
                .transactionManager(transactionManager)
                .reader(thesesReader)
                .processor(thesisProcessor)
                .writer(datagouvCompositeWriter)
                .stream(datagouvJsonlWriter)
                .stream(datagouvJsonWriter)
                .build();
    }

    /**
     * Tasklet générant le CSV par aplatissement du JSONL.
     */
    @Bean
    @StepScope
    public DatagouvCsvTasklet csvTasklet(
            @Value("${app.output-dir:output}") String outputDir,
            RunTimestamp runTimestamp,
            JsonMapper jsonMapper) {
        return new DatagouvCsvTasklet(DatagouvOutputListener.tmpDir(outputDir).getPath(), runTimestamp.value(), jsonMapper);
    }

    /**
     * Étape 2 : Génération du CSV à partir du JSONL.
     */
    @Bean
    public Step csvStep(JobRepository jobRepository,
                        PlatformTransactionManager transactionManager,
                        DatagouvCsvTasklet csvTasklet) {
        return new StepBuilder("csvStep", jobRepository)
                .tasklet(csvTasklet, transactionManager)
                .build();
    }

    /**
     * Tasklet compressant le fichier JSONL en JSONL.gz.
     */
    @Bean
    @StepScope
    public DatagouvGzipTasklet gzipTasklet(
            @Value("${app.output-dir:output}") String outputDir,
            RunTimestamp runTimestamp) {
        return new DatagouvGzipTasklet(DatagouvOutputListener.tmpDir(outputDir).getPath(), runTimestamp.value());
    }

    /**
     * Étape 3 : Compression du fichier JSONL.
     */
    @Bean
    public Step gzipStep(JobRepository jobRepository,
                         PlatformTransactionManager transactionManager,
                         DatagouvGzipTasklet gzipTasklet) {
        return new StepBuilder("gzipStep", jobRepository)
                .tasklet(gzipTasklet, transactionManager)
                .build();
    }

    /**
     * Tasklet de téléversement vers data.gouv.fr.
     */
    @Bean
    @StepScope
    public DatagouvUploadTasklet uploadTasklet(
            RestClient dataGouvRestClient,
            @Value("${app.datagouv.base-url:}") String baseUrl,
            @Value("${app.datagouv.dataset-id:}") String datasetId,
            @Value("${app.datagouv.csv-resource-id:}") String csvResourceId,
            @Value("${app.datagouv.json-resource-id:}") String jsonResourceId,
            @Value("${app.datagouv.jsonl-resource-id:}") String jsonlResourceId,
            @Value("${app.datagouv.jsonl-gz-resource-id:}") String jsonlGzResourceId,
            @Value("${app.datagouv.api-key:}") String apiKey,
            @Value("${app.output-dir:output}") String outputDir,
            @Value("${app.datagouv.upload-enabled:false}") boolean uploadEnabled,
            @Value("${app.datagouv.mode-test:false}") boolean modeTest,
            @Value("${app.datagouv.upload-chunk-size:10MB}") DataSize uploadChunkSize,
            RunTimestamp runTimestamp) {
        return new DatagouvUploadTasklet(dataGouvRestClient, baseUrl, datasetId, csvResourceId, jsonResourceId,
                jsonlResourceId, jsonlGzResourceId, apiKey, DatagouvOutputListener.tmpDir(outputDir).getPath(), uploadEnabled, modeTest, runTimestamp.value(),
                Math.toIntExact(uploadChunkSize.toBytes()));
    }

    /**
     * Étape 4 : Exécution de la Tasklet d'upload vers data.gouv.fr.
     */
    @Bean
    public Step uploadStep(JobRepository jobRepository,
                           PlatformTransactionManager transactionManager,
                           DatagouvUploadTasklet uploadTasklet) {
        return new StepBuilder("uploadStep", jobRepository)
                .tasklet(uploadTasklet, transactionManager)
                .build();
    }

    /**
     * Listener rangeant les fichiers du run dans datagouv/success, test ou errors en fin de job.
     */
    @Bean
    public DatagouvOutputListener datagouvOutputListener(
            @Value("${app.output-dir:output}") String outputDir,
            RunTimestamp runTimestamp,
            @Value("${app.datagouv.log-retention:30d}") Duration logRetention,
            @Value("${app.datagouv.upload-enabled:false}") boolean uploadEnabled,
            @Value("${app.datagouv.mode-test:false}") boolean modeTest) {
        return new DatagouvOutputListener(outputDir, runTimestamp.value(), logRetention, uploadEnabled && !modeTest);
    }

    /**
     * Définition du Job Spring Batch thesesDumpDataGouvJob enchaînant la génération et l'envoi.
     */
    @Bean
    public Job thesesDumpDataGouvJob(JobRepository jobRepository,
                                     @Qualifier("fetchAndWriteStep") Step fetchAndWriteStep,
                                     @Qualifier("csvStep") Step csvStep,
                                     @Qualifier("gzipStep") Step gzipStep,
                                     @Qualifier("uploadStep") Step uploadStep,
                                     DatagouvOutputListener datagouvOutputListener) {
        return new JobBuilder("thesesDumpDataGouvJob", jobRepository)
                .incrementer(new RunIdIncrementer())
                .listener(datagouvOutputListener)
                .start(fetchAndWriteStep)
                .next(csvStep)
                .next(gzipStep)
                .next(uploadStep)
                .build();
    }
}

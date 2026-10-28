package fr.theses.batch.job.datagouv.configuration;

import fr.theses.batch.business.datagouv.model.ExportThesis;
import fr.theses.batch.business.datagouv.model.Thesis;
import fr.theses.batch.job.RunTimestamp;
import fr.theses.batch.job.datagouv.processor.ThesisProcessor;
import fr.theses.batch.job.datagouv.reader.ThesesItemReader;
import fr.theses.batch.job.datagouv.tasklet.GzipTasklet;
import fr.theses.batch.job.datagouv.tasklet.UploadTasklet;
import fr.theses.batch.job.datagouv.writer.CsvTruncationReportListener;
import fr.theses.batch.job.datagouv.writer.JsonlLineAggregator;
import fr.theses.batch.job.datagouv.writer.ThesisCsvLineAggregator;
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
import java.nio.file.Path;
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
     * UploadTasklet découpe les gros fichiers : le tampon ne dépasse pas la taille d'un morceau.
     */
    @Bean
    public RestClient dataGouvRestClient() {
        return RestClient.builder()
                .requestFactory(new BufferingClientHttpRequestFactory(new SimpleClientHttpRequestFactory()))
                .build();
    }

    /**
     * Reader récupérant les thèses soutenues de façon paginée depuis theses.fr.
     */
    @Bean
    public ThesesItemReader thesesReader(
            RestClient thesesRestClient,
            @Value("${app.theses.page-size:1000}") int pageSize,
            @Value("${app.datagouv.mode-test:${app.mode-test:${MODE_TEST:false}}}") boolean modeTest) {
        return new ThesesItemReader(thesesRestClient, pageSize, modeTest);
    }

    /**
     * Processeur nettoyant et harmonisant les données des thèses avant écriture.
     */
    @Bean
    public ThesisProcessor thesisProcessor() {
        return new ThesisProcessor();
    }

    /**
     * Agrégateur des lignes CSV, qui compte aussi les valeurs tronquées pendant l'étape.
     */
    @Bean
    @StepScope
    public ThesisCsvLineAggregator datagouvCsvLineAggregator() {
        return new ThesisCsvLineAggregator();
    }

    /**
     * Rapport des valeurs tronquées dans le CSV, écrit en fin d'étape à côté des exports.
     */
    @Bean
    @StepScope
    public CsvTruncationReportListener csvTruncationReportListener(
            ThesisCsvLineAggregator datagouvCsvLineAggregator,
            @Value("${app.output-dir:output}") String outputDir,
            RunTimestamp runTimestamp) {
        return new CsvTruncationReportListener(datagouvCsvLineAggregator,
                Path.of(outputDir, runTimestamp.value() + "_datagouv_troncatures.txt"));
    }

    /**
     * Writer écrivant les thèses au format CSV avec BOM UTF-8 et en-tête des 213 colonnes.
     */
    @Bean
    @StepScope
    public FlatFileItemWriter<ExportThesis> datagouvCsvWriter(
            @Value("${app.output-dir:output}") String outputDir,
            RunTimestamp runTimestamp,
            ThesisCsvLineAggregator datagouvCsvLineAggregator) {
        return new FlatFileItemWriterBuilder<ExportThesis>()
                .name("datagouvCsvWriter")
                .resource(new FileSystemResource(new File(outputDir, runTimestamp.value() + "_datagouv_results.csv")))
                .encoding("UTF-8")
                .headerCallback(headerWriter -> headerWriter.write(
                        "\uFEFF\"accessible\",\"auteur.idref\",\"auteur.nom\",\"auteur.prenom\",\"cas\",\"code_etab\",\"date_soutenance\",\"directeurs_these.0.idref\",\"directeurs_these.0.nom\",\"directeurs_these.0.prenom\",\"directeurs_these.1.idref\",\"directeurs_these.1.nom\",\"directeurs_these.1.prenom\",\"directeurs_these.2.idref\",\"directeurs_these.2.nom\",\"directeurs_these.2.prenom\",\"directeurs_these.3.idref\",\"directeurs_these.3.nom\",\"directeurs_these.3.prenom\",\"directeurs_these.4.idref\",\"directeurs_these.4.nom\",\"directeurs_these.4.prenom\",\"directeurs_these.5.idref\",\"directeurs_these.5.nom\",\"directeurs_these.5.prenom\",\"directeurs_these.6.idref\",\"directeurs_these.6.nom\",\"directeurs_these.6.prenom\",\"discipline\",\"ecoles_doctorales.0.idref\",\"ecoles_doctorales.0.nom\",\"ecoles_doctorales.1.idref\",\"ecoles_doctorales.1.nom\",\"embargo\",\"etablissements_soutenance.0.idref\",\"etablissements_soutenance.0.nom\",\"etablissements_soutenance.1.idref\",\"etablissements_soutenance.1.nom\",\"etablissements_soutenance.2.idref\",\"etablissements_soutenance.2.nom\",\"etablissements_soutenance.3.idref\",\"etablissements_soutenance.3.nom\",\"etablissements_soutenance.4.idref\",\"etablissements_soutenance.4.nom\",\"langues.0\",\"langues.1\",\"langues.2\",\"langues.3\",\"membres_jury.0.idref\",\"membres_jury.0.nom\",\"membres_jury.0.prenom\",\"membres_jury.10.idref\",\"membres_jury.10.nom\",\"membres_jury.10.prenom\",\"membres_jury.11.idref\",\"membres_jury.11.nom\",\"membres_jury.11.prenom\",\"membres_jury.1.idref\",\"membres_jury.1.nom\",\"membres_jury.1.prenom\",\"membres_jury.2.idref\",\"membres_jury.2.nom\",\"membres_jury.2.prenom\",\"membres_jury.3.idref\",\"membres_jury.3.nom\",\"membres_jury.3.prenom\",\"membres_jury.4.idref\",\"membres_jury.4.nom\",\"membres_jury.4.prenom\",\"membres_jury.5.idref\",\"membres_jury.5.nom\",\"membres_jury.5.prenom\",\"membres_jury.6.idref\",\"membres_jury.6.nom\",\"membres_jury.6.prenom\",\"membres_jury.7.idref\",\"membres_jury.7.nom\",\"membres_jury.7.prenom\",\"membres_jury.8.idref\",\"membres_jury.8.nom\",\"membres_jury.8.prenom\",\"membres_jury.9.idref\",\"membres_jury.9.nom\",\"membres_jury.9.prenom\",\"nnt\",\"oai_set_specs\",\"partenaires_recherche.0.idref\",\"partenaires_recherche.0.nom\",\"partenaires_recherche.0.type\",\"partenaires_recherche.1.idref\",\"partenaires_recherche.1.nom\",\"partenaires_recherche.1.type\",\"partenaires_recherche.2.idref\",\"partenaires_recherche.2.nom\",\"partenaires_recherche.2.type\",\"partenaires_recherche.3.idref\",\"partenaires_recherche.3.nom\",\"partenaires_recherche.3.type\",\"partenaires_recherche.4.idref\",\"partenaires_recherche.4.nom\",\"partenaires_recherche.4.type\",\"partenaires_recherche.5.idref\",\"partenaires_recherche.5.nom\",\"partenaires_recherche.5.type\",\"partenaires_recherche.6.idref\",\"partenaires_recherche.6.nom\",\"partenaires_recherche.6.type\",\"partenaires_recherche.7.idref\",\"partenaires_recherche.7.nom\",\"partenaires_recherche.7.type\",\"president_jury.idref\",\"president_jury.nom\",\"president_jury.prenom\",\"rapporteurs.0.idref\",\"rapporteurs.0.nom\",\"rapporteurs.0.prenom\",\"rapporteurs.1.idref\",\"rapporteurs.1.nom\",\"rapporteurs.1.prenom\",\"rapporteurs.2.idref\",\"rapporteurs.2.nom\",\"rapporteurs.2.prenom\",\"rapporteurs.3.idref\",\"rapporteurs.3.nom\",\"rapporteurs.3.prenom\",\"rapporteurs.4.idref\",\"rapporteurs.4.nom\",\"rapporteurs.4.prenom\",\"rapporteurs.5.idref\",\"rapporteurs.5.nom\",\"rapporteurs.5.prenom\",\"resumes.autre.0\",\"resumes.autre.1\",\"resumes.autre.2\",\"resumes.autre.3\",\"resumes.autre.4\",\"resumes.autre.5\",\"resumes.en\",\"resumes.fr\",\"source\",\"status\",\"sujets.autre.0\",\"sujets.autre.1\",\"sujets.autre.2\",\"sujets.autre.3\",\"sujets.autre.4\",\"sujets.autre.5\",\"sujets.autre.6\",\"sujets.autre.7\",\"sujets.en\",\"sujets.fr\",\"sujets_rameau.0\",\"sujets_rameau.1\",\"sujets_rameau.10\",\"sujets_rameau.11\",\"sujets_rameau.12\",\"sujets_rameau.13\",\"sujets_rameau.14\",\"sujets_rameau.15\",\"sujets_rameau.16\",\"sujets_rameau.17\",\"sujets_rameau.18\",\"sujets_rameau.19\",\"sujets_rameau.2\",\"sujets_rameau.20\",\"sujets_rameau.21\",\"sujets_rameau.22\",\"sujets_rameau.23\",\"sujets_rameau.24\",\"sujets_rameau.25\",\"sujets_rameau.26\",\"sujets_rameau.27\",\"sujets_rameau.28\",\"sujets_rameau.29\",\"sujets_rameau.3\",\"sujets_rameau.30\",\"sujets_rameau.31\",\"sujets_rameau.32\",\"sujets_rameau.33\",\"sujets_rameau.34\",\"sujets_rameau.35\",\"sujets_rameau.36\",\"sujets_rameau.37\",\"sujets_rameau.38\",\"sujets_rameau.39\",\"sujets_rameau.4\",\"sujets_rameau.40\",\"sujets_rameau.41\",\"sujets_rameau.42\",\"sujets_rameau.43\",\"sujets_rameau.44\",\"sujets_rameau.45\",\"sujets_rameau.46\",\"sujets_rameau.47\",\"sujets_rameau.48\",\"sujets_rameau.49\",\"sujets_rameau.5\",\"sujets_rameau.50\",\"sujets_rameau.51\",\"sujets_rameau.52\",\"sujets_rameau.53\",\"sujets_rameau.54\",\"sujets_rameau.6\",\"sujets_rameau.7\",\"sujets_rameau.8\",\"sujets_rameau.9\",\"these_sur_travaux\",\"titres.autre.0\",\"titres.autre.1\",\"titres.autre.2\",\"titres.autre.3\",\"titres.en\",\"titres.fr\""))
                .lineAggregator(datagouvCsvLineAggregator)
                .build();
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
                .resource(new FileSystemResource(new File(outputDir, runTimestamp.value() + "_datagouv_results.jsonl")))
                .encoding("UTF-8")
                .lineAggregator(new JsonlLineAggregator(jsonMapper))
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
                .resource(new FileSystemResource(new File(outputDir, runTimestamp.value() + "_datagouv_results.json")))
                .jsonObjectMarshaller(new JacksonJsonObjectMarshaller<>(jsonMapper))
                .build();
    }

    /**
     * Writer composite distribuant chaque thèse lue aux 3 formats simultanés (CSV, JSONL, JSON).
     */
    @Bean
    @StepScope
    public CompositeItemWriter<ExportThesis> datagouvCompositeWriter(
            @Qualifier("datagouvCsvWriter") FlatFileItemWriter<ExportThesis> datagouvCsvWriter,
            @Qualifier("datagouvJsonlWriter") FlatFileItemWriter<ExportThesis> datagouvJsonlWriter,
            @Qualifier("datagouvJsonWriter") JsonFileItemWriter<ExportThesis> datagouvJsonWriter) {
        CompositeItemWriter<ExportThesis> writer = new CompositeItemWriter<>();
        writer.setDelegates(List.of(datagouvCsvWriter, datagouvJsonlWriter, datagouvJsonWriter));
        return writer;
    }

    /**
     * Étape 1 : Lecture paginée de l'API et écriture simultanée dans les 3 fichiers d'export.
     */
    @Bean
    public Step fetchAndWriteStep(JobRepository jobRepository,
                                  PlatformTransactionManager transactionManager,
                                  ThesesItemReader thesesReader,
                                  ThesisProcessor thesisProcessor,
                                  @Qualifier("datagouvCompositeWriter") CompositeItemWriter<ExportThesis> datagouvCompositeWriter,
                                  @Qualifier("datagouvCsvWriter") FlatFileItemWriter<ExportThesis> datagouvCsvWriter,
                                  @Qualifier("datagouvJsonlWriter") FlatFileItemWriter<ExportThesis> datagouvJsonlWriter,
                                  @Qualifier("datagouvJsonWriter") JsonFileItemWriter<ExportThesis> datagouvJsonWriter,
                                  CsvTruncationReportListener csvTruncationReportListener) {
        return new StepBuilder("fetchAndWriteStep", jobRepository)
                .<Thesis, ExportThesis>chunk(100)
                .transactionManager(transactionManager)
                .reader(thesesReader)
                .processor(thesisProcessor)
                .writer(datagouvCompositeWriter)
                .stream(datagouvCsvWriter)
                .stream(datagouvJsonlWriter)
                .stream(datagouvJsonWriter)
                .listener(csvTruncationReportListener)
                .build();
    }

    /**
     * Tasklet compressant le fichier JSONL en JSONL.gz.
     */
    @Bean
    @StepScope
    public GzipTasklet gzipTasklet(
            @Value("${app.output-dir:output}") String outputDir,
            RunTimestamp runTimestamp) {
        return new GzipTasklet(outputDir, runTimestamp.value());
    }

    /**
     * Étape 2 : Compression du fichier JSONL.
     */
    @Bean
    public Step gzipStep(JobRepository jobRepository,
                         PlatformTransactionManager transactionManager,
                         GzipTasklet gzipTasklet) {
        return new StepBuilder("gzipStep", jobRepository)
                .tasklet(gzipTasklet, transactionManager)
                .build();
    }

    /**
     * Tasklet de téléversement vers data.gouv.fr.
     */
    @Bean
    @StepScope
    public UploadTasklet uploadTasklet(
            RestClient dataGouvRestClient,
            @Value("${app.datagouv.dataset-id:}") String datasetId,
            @Value("${app.datagouv.csv-resource-id:}") String csvResourceId,
            @Value("${app.datagouv.json-resource-id:}") String jsonResourceId,
            @Value("${app.datagouv.jsonl-resource-id:}") String jsonlResourceId,
            @Value("${app.datagouv.jsonl-gz-resource-id:}") String jsonlGzResourceId,
            @Value("${app.datagouv.api-key:}") String apiKey,
            @Value("${app.output-dir:output}") String outputDir,
            @Value("${app.datagouv.upload-enabled:false}") boolean uploadEnabled,
            @Value("${app.datagouv.upload-chunk-size:10MB}") DataSize uploadChunkSize,
            RunTimestamp runTimestamp) {
        return new UploadTasklet(dataGouvRestClient, datasetId, csvResourceId, jsonResourceId,
                jsonlResourceId, jsonlGzResourceId, apiKey, outputDir, uploadEnabled, runTimestamp.value(),
                Math.toIntExact(uploadChunkSize.toBytes()));
    }

    /**
     * Étape 3 : Exécution de la Tasklet d'upload vers data.gouv.fr.
     */
    @Bean
    public Step uploadStep(JobRepository jobRepository,
                           PlatformTransactionManager transactionManager,
                           UploadTasklet uploadTasklet) {
        return new StepBuilder("uploadStep", jobRepository)
                .tasklet(uploadTasklet, transactionManager)
                .build();
    }

    /**
     * Définition du Job Spring Batch thesesDumpDataGouvJob enchaînant la génération et l'envoi.
     */
    @Bean
    public Job thesesDumpDataGouvJob(JobRepository jobRepository,
                                     @Qualifier("fetchAndWriteStep") Step fetchAndWriteStep,
                                     @Qualifier("gzipStep") Step gzipStep,
                                     @Qualifier("uploadStep") Step uploadStep) {
        return new JobBuilder("thesesDumpDataGouvJob", jobRepository)
                .incrementer(new RunIdIncrementer())
                .start(fetchAndWriteStep)
                .next(gzipStep)
                .next(uploadStep)
                .build();
    }
}

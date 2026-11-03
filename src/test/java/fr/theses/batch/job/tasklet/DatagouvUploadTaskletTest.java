package fr.theses.batch.job.tasklet;

import org.hamcrest.Matcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Tests unitaires de DatagouvUploadTasklet.
 */
class DatagouvUploadTaskletTest {

    private static final String RUN = "2026-01-01-12-00-00";
    // « / » final volontaire : il doit être retiré pour ne pas produire « //api »
    private static final String DATA_GOUV_URL = "https://demo.data.gouv.fr/";
    private static final String BASE_URL = "https://demo.data.gouv.fr/api/1/datasets/datasetId/resources/";

    @TempDir
    Path tempDir;

    @Test
    void execute_quandUploadDesactive_termineSansErreur() throws IOException {
        RestClient restClient = mock(RestClient.class);
        DatagouvUploadTasklet tasklet = tasklet(restClient, false);

        RepeatStatus status = tasklet.execute(mock(StepContribution.class), mock(ChunkContext.class));
        assertThat(status).isEqualTo(RepeatStatus.FINISHED);
    }

    @Test
    void execute_enModeTest_nEnvoieRienMemeSiUploadActive() throws IOException {
        createExports();
        RestClient.Builder builder = RestClient.builder();
        // Aucune requête attendue : un envoi ferait échouer server.verify()
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DatagouvUploadTasklet tasklet = new DatagouvUploadTasklet(builder.build(), DATA_GOUV_URL, "datasetId", "csvId", "jsonId", "jsonlId",
                "jsonlGzId", "apiKey", tempDir.toString(), true, true, RUN, 1024 * 1024);

        RepeatStatus status = tasklet.execute(mock(StepContribution.class), mock(ChunkContext.class));

        assertThat(status).isEqualTo(RepeatStatus.FINISHED);
        server.verify();
    }

    @Test
    void execute_quandUploadActiveMaisFichierInexistant_leveException() {
        RestClient restClient = mock(RestClient.class);
        DatagouvUploadTasklet tasklet = tasklet(restClient, true);

        assertThatThrownBy(() -> tasklet.execute(mock(StepContribution.class), mock(ChunkContext.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("aucun fichier envoyé")
                .hasMessageContaining("_datagouv_results.csv absent");
    }

    @Test
    void execute_urlDataGouvVide_echoueAvantToutEnvoi() throws IOException {
        createExports();
        RestClient.Builder builder = RestClient.builder();
        // Aucune requête attendue : un envoi ferait échouer server.verify()
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DatagouvUploadTasklet tasklet = new DatagouvUploadTasklet(builder.build(), "", "datasetId", "csvId", "jsonId", "jsonlId",
                "jsonlGzId", "apiKey", tempDir.toString(), true, false, RUN, 1024 * 1024);

        assertThatThrownBy(() -> tasklet.execute(mock(StepContribution.class), mock(ChunkContext.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DATA_GOUV_BASE_URL non renseignée");
        server.verify();
    }

    @Test
    void execute_identifiantDeRessourceVide_echoueAvantToutEnvoi() throws IOException {
        createExports();
        RestClient.Builder builder = RestClient.builder();
        // Aucune requête attendue : un envoi partiel ferait échouer server.verify()
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DatagouvUploadTasklet tasklet = new DatagouvUploadTasklet(builder.build(), DATA_GOUV_URL, "datasetId", "csvId", "jsonId", "jsonlId",
                "", "apiKey", tempDir.toString(), true, false, RUN, 1024 * 1024);

        assertThatThrownBy(() -> tasklet.execute(mock(StepContribution.class), mock(ChunkContext.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DATA_GOUV_JSONL_GZ_RESOURCE_ID non renseignée");
        server.verify();
    }

    @Test
    void execute_quandUploadActive_televerseLes4FichiersEnMultipart() throws IOException {
        createExports();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        expectUpload(server, "csvId", "theses-soutenues.csv", "csv;content");
        expectUpload(server, "jsonId", "theses-soutenues.json", "[{\"json\":true}]");
        expectUpload(server, "jsonlId", "theses-soutenues.jsonl", "{\"jsonl\":true}");
        expectUpload(server, "jsonlGzId", "theses-soutenues.jsonl.gz", "gz-content");

        DatagouvUploadTasklet tasklet = tasklet(builder.build(), true);

        RepeatStatus status = tasklet.execute(mock(StepContribution.class), mock(ChunkContext.class));

        assertThat(status).isEqualTo(RepeatStatus.FINISHED);
        server.verify();
    }

    @Test
    void execute_quandDataGouvRefuse_propageLErreur() throws IOException {
        createExports();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + "csvId/upload/"))
                .andRespond(withBadRequest().body("{\"error\": \"Missing file parameter\"}"));

        DatagouvUploadTasklet tasklet = tasklet(builder.build(), true);

        assertThatThrownBy(() -> tasklet.execute(mock(StepContribution.class), mock(ChunkContext.class)))
                .isInstanceOf(HttpClientErrorException.BadRequest.class);
    }

    @Test
    void execute_echecApresUnePremiereRessource_signaleLaPublicationPartielle() throws IOException {
        createExports();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        expectUpload(server, "csvId", "theses-soutenues.csv", "csv;content");
        server.expect(requestTo(BASE_URL + "jsonId/upload/")).andRespond(withBadRequest());

        DatagouvUploadTasklet tasklet = tasklet(builder.build(), true);

        // Le CSV est parti, le JSON est refusé : l'erreur est propagée et rien n'est envoyé ensuite
        assertThatThrownBy(() -> tasklet.execute(mock(StepContribution.class), mock(ChunkContext.class)))
                .isInstanceOf(HttpClientErrorException.BadRequest.class);
        server.verify();
    }

    @Test
    void execute_fichierPlusGrosQueUnMorceau_televerseEnMorceauxPuisAssemble() throws IOException {
        createExports();
        // 25 octets avec des morceaux de 10 : 3 morceaux (10, 10, 5) puis la requête d'assemblage
        Files.writeString(tempDir.resolve(RUN + "_datagouv_results.csv"), "AAAAAAAAAABBBBBBBBBBCCCCC");
        // Fichiers de 10 octets au plus : une seule requête chacun
        Files.writeString(tempDir.resolve(RUN + "_datagouv_results.json"), "[]");
        Files.writeString(tempDir.resolve(RUN + "_datagouv_results.jsonl"), "{}");
        Files.writeString(tempDir.resolve(RUN + "_datagouv_results.jsonl.gz"), "gz");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        expectChunk(server, 0, "10", "AAAAAAAAAA");
        expectChunk(server, 1, "10", "BBBBBBBBBB");
        expectChunk(server, 2, "5", "CCCCC");
        server.expect(requestTo(BASE_URL + "csvId/upload/"))
                .andExpect(content().string(allOf(
                        field("filename", "theses-soutenues.csv"),
                        field("totalparts", "3"),
                        containsString("name=\"uuid\""),
                        not(containsString("name=\"file\"")))))
                .andRespond(withSuccess());
        expectUpload(server, "jsonId", "theses-soutenues.json", "[]");
        expectUpload(server, "jsonlId", "theses-soutenues.jsonl", "{}");
        expectUpload(server, "jsonlGzId", "theses-soutenues.jsonl.gz", "gz");

        DatagouvUploadTasklet tasklet = new DatagouvUploadTasklet(builder.build(), DATA_GOUV_URL, "datasetId", "csvId", "jsonId", "jsonlId",
                "jsonlGzId", "apiKey", tempDir.toString(), true, false, RUN, 10);

        tasklet.execute(mock(StepContribution.class), mock(ChunkContext.class));

        server.verify();
    }

    @Test
    void constructeur_tailleDeMorceauNulle_leveIllegalArgumentException() {
        RestClient restClient = mock(RestClient.class);
        String outputDir = tempDir.toString();
        assertThatThrownBy(() -> new DatagouvUploadTasklet(restClient, DATA_GOUV_URL, "datasetId", "csvId", "jsonId", "jsonlId",
                "jsonlGzId", "apiKey", outputDir, true, false, RUN, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private DatagouvUploadTasklet tasklet(RestClient restClient, boolean uploadEnabled) {
        return new DatagouvUploadTasklet(restClient, DATA_GOUV_URL, "datasetId", "csvId", "jsonId", "jsonlId",
                "jsonlGzId", "apiKey", tempDir.toString(), uploadEnabled, false, RUN, 1024 * 1024);
    }

    private void createExports() throws IOException {
        Files.writeString(tempDir.resolve(RUN + "_datagouv_results.csv"), "csv;content");
        Files.writeString(tempDir.resolve(RUN + "_datagouv_results.json"), "[{\"json\":true}]");
        Files.writeString(tempDir.resolve(RUN + "_datagouv_results.jsonl"), "{\"jsonl\":true}");
        Files.writeString(tempDir.resolve(RUN + "_datagouv_results.jsonl.gz"), "gz-content");
    }

    /**
     * Attend l'envoi d'un morceau du CSV : champs du protocole udata et contenu du morceau seul.
     */
    private void expectChunk(MockRestServiceServer server, int partIndex, String chunkSize, String chunkContent) {
        server.expect(requestTo(BASE_URL + "csvId/upload/"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-API-KEY", "apiKey"))
                .andExpect(content().string(allOf(
                        field("filename", "theses-soutenues.csv"),
                        field("totalparts", "3"),
                        field("partindex", String.valueOf(partIndex)),
                        field("chunksize", chunkSize),
                        containsString("name=\"uuid\""),
                        part("name=\"file\"; filename=\"theses-soutenues.csv\"", chunkContent))))
                .andRespond(withSuccess());
    }

    /**
     * Partie multipart texte "name" valant exactement "value".
     */
    private static Matcher<String> field(String name, String value) {
        return part("name=\"" + name + "\"", value);
    }

    /**
     * Partie multipart dont le Content-Disposition contient "disposition" et dont le contenu vaut
     * exactement "value" (les autres en-têtes de la partie sont ignorés).
     */
    private static Matcher<String> part(String disposition, String value) {
        return matchesPattern(Pattern.compile("(?s).*" + Pattern.quote(disposition) + "(?:\r\n[^\r\n]+)*\r\n\r\n"
                + Pattern.quote(value) + "\r\n.*"));
    }

    /**
     * Attend une requête multipart portant le fichier dans la partie "file" sous son nom data.gouv.fr.
     */
    private void expectUpload(MockRestServiceServer server, String resourceId, String uploadName, String fileContent) {
        server.expect(requestTo(BASE_URL + resourceId + "/upload/"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-API-KEY", "apiKey"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.MULTIPART_FORM_DATA))
                .andExpect(content().string(allOf(
                        containsString("name=\"file\""),
                        containsString("filename=\"" + uploadName + "\""),
                        containsString(fileContent))))
                .andRespond(withSuccess());
    }
}

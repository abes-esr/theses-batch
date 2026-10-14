package fr.theses.batch.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Teste le client contre un vrai serveur HTTP local, pour couvrir la configuration du RestClient
 * (timeouts, convertisseur JSON acceptant text/plain) et pas seulement la logique de parsing.
 * Le RestClient.Builder vient de l'auto-configuration Spring Boot, comme en production :
 * un RestClient.builder() nu n'a pas les convertisseurs JSON qu'elle enregistre.
 */
class SolrClientTest {

    private static AnnotationConfigApplicationContext bootContext;

    private HttpServer server;
    private final AtomicReference<String> lastQuery = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String contentType = "application/json";
    private volatile String body = "{}";

    @BeforeAll
    static void startBootContext() {
        bootContext = new AnnotationConfigApplicationContext(JacksonAutoConfiguration.class,
                HttpMessageConvertersAutoConfiguration.class, RestClientAutoConfiguration.class);
    }

    @AfterAll
    static void closeBootContext() {
        bootContext.close();
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/solr/select", exchange -> {
            lastQuery.set(URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private SolrClient client(boolean enabled, String baseUrl) {
        SolrClient client = new SolrClient(bootContext.getBean(RestClient.Builder.class));
        ReflectionTestUtils.setField(client, "enabled", enabled);
        ReflectionTestUtils.setField(client, "baseUrl", baseUrl);
        ReflectionTestUtils.setField(client, "connectTimeout", 1000);
        ReflectionTestUtils.setField(client, "readTimeout", 1000);
        client.init();
        return client;
    }

    private SolrClient client() {
        return client(true, "http://localhost:" + server.getAddress().getPort() + "/solr");
    }

    @Test
    void desactive_neContactePasSolr() {
        SolrClient client = client(false, "http://localhost:" + server.getAddress().getPort() + "/solr");

        assertThat(client.isEnabled()).isFalse();
        assertThat(client.query("*:*")).isEmpty();
        assertThat(lastQuery.get()).isNull();
    }

    @Test
    void query_transmetLesParametresEtRenvoieLaReponse() {
        body = "{\"response\":{\"numFound\":1,\"docs\":[{\"nnt\":\"2018PA01\"}]}}";

        Map<String, Object> response = client().query("id:1", "nnt,doi", 5);

        assertThat(response).containsKey("response");
        assertThat(lastQuery.get()).contains("q=id:1", "fl=nnt,doi", "rows=5", "wt=json");
    }

    @Test
    void query_accepteDuJsonServiEnTextPlain() {
        contentType = "text/plain;charset=utf-8";
        body = "{\"response\":{\"docs\":[]}}";

        assertThat(client().query("*:*")).containsKey("response");
        assertThat(lastQuery.get()).contains("fl=*", "rows=1");
    }

    @Test
    void queryById_echappeLaValeurEtRenvoieLePremierDocument() {
        body = "{\"response\":{\"docs\":[{\"nnt\":\"A\"},{\"nnt\":\"B\"}]}}";

        Map<String, Object> document = client().queryById("id", "a\"b\\c", "nnt");

        assertThat(document).containsEntry("nnt", "A");
        assertThat(lastQuery.get()).contains("q=id:\"a\\\"b\\\\c\"");
    }

    @Test
    void queryById_valeurNull_requeteSurChaineVide() {
        body = "{\"response\":{\"docs\":[]}}";

        assertThat(client().queryById("id", null, "nnt")).isEmpty();
        assertThat(lastQuery.get()).contains("q=id:\"\"");
    }

    @Test
    void queryById_reponseVide_renvoieMapVide() {
        assertThat(client(false, "http://localhost:1/solr").queryById("id", "1", "nnt")).isEmpty();
    }

    @Test
    void erreurHttp_renvoieMapVide() {
        status = 500;
        contentType = "text/html";
        body = "<html>boom</html>";

        assertThat(client().query("*:*")).isEmpty();
    }

    @Test
    void serveurInjoignable_renvoieMapVide() {
        int port = server.getAddress().getPort();
        server.stop(0);

        assertThat(client(true, "http://localhost:" + port + "/solr").query("*:*")).isEmpty();
    }

    @Test
    void getBaseUrl_ajouteLeSchemaSiAbsent() {
        assertThat(client(false, "solr.example.org/solr").getBaseUrl()).isEqualTo("http://solr.example.org/solr");
        assertThat(client(false, "https://solr.example.org").getBaseUrl()).isEqualTo("https://solr.example.org");
        assertThat(client(false, "http://solr.example.org").getBaseUrl()).isEqualTo("http://solr.example.org");
    }

    @Test
    void extractFirstDocument_reponsesInvalides() {
        SolrClient client = client(false, "http://localhost");

        assertThat(client.extractFirstDocument(Map.of())).isEmpty();
        assertThat(client.extractFirstDocument(Map.of("response", "texte"))).isEmpty();
        assertThat(client.extractFirstDocument(Map.of("response", Map.of()))).isEmpty();
        assertThat(client.extractFirstDocument(Map.of("response", Map.of("docs", "texte")))).isEmpty();
        assertThat(client.extractFirstDocument(Map.of("response", Map.of("docs", List.of())))).isEmpty();
        assertThat(client.extractFirstDocument(Map.of("response", Map.of("docs", List.of("texte"))))).isEmpty();
        assertThat(client.extractFirstDocument(Map.of("response", Map.of("docs", List.of(Map.of("a", 1))))))
                .containsEntry("a", 1);
    }

    @Test
    void extractFieldValue_selonLeTypeDuChamp() {
        SolrClient client = client(false, "http://localhost");
        Map<String, Object> document = new HashMap<>();
        document.put("texte", "valeur");
        document.put("liste", List.of("premier", "second"));
        document.put("listeVide", List.of());
        document.put("listeNombres", List.of(1, 2));
        document.put("nombre", 42);
        document.put("nul", null);

        assertThat(client.extractFieldValue(document, "texte")).isEqualTo("valeur");
        assertThat(client.extractFieldValue(document, "liste")).isEqualTo("premier");
        assertThat(client.extractFieldValue(document, "listeVide")).isEqualTo("[]");
        assertThat(client.extractFieldValue(document, "listeNombres")).isEqualTo("[1, 2]");
        assertThat(client.extractFieldValue(document, "nombre")).isEqualTo("42");
        assertThat(client.extractFieldValue(document, "nul")).isNull();
        assertThat(client.extractFieldValue(document, "absent")).isNull();
        assertThat(client.extractFieldValue(document, null)).isNull();
        assertThat(client.extractFieldValue(null, "texte")).isNull();
    }
}

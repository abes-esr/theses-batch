package fr.theses.batch.job;

import com.sun.net.httpserver.HttpServer;
import fr.theses.batch.support.TestPdfs;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.test.JobOperatorTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exécute le job thesesDumpDataGouvJob complet (lecture paginée → traitement → écriture CSV / JSON / JSONL)
 * contre un faux serveur theses.fr local, téléversement data.gouv.fr désactivé.
 */
@SpringBootTest
@SpringBatchTest
class DatagouvJobIntegrationTest {

    private static final String RUN = "2026-02-03-04-05-06";

    private static final String THESES_JSON = """
            {"totalHits": 2, "theses": [
              {"id": "s1", "nnt": "2023MONT0001", "titrePrincipal": "Titre un",
               "titres": {"fr": "Titre un", "en": "Title one", "es": "Título uno"},
               "auteurs": [{"nom": "Dupont", "prenom": "Jean", "ppn": "111111111"},
                           {"nom": "Durand", "prenom": "Julie", "ppn": "121212121"}],
               "dateSoutenance": "15/06/2023", "status": "soutenue", "accessible": "oui",
               "etabSoutenance": {"nom": "Université de Montpellier", "ppn": "333333333"},
               "etabCotutelle": [{"nom": "Université Laval", "ppn": "444444444"}],
               "ecolesDoctorales": [{"nom": "ED 60", "ppn": "555555555"}],
               "mapSujets": {"fr": [{"keyword": "Rameau un", "type": "sujetsRameau"},
                                    {"keyword": "Polymères", "type": "sujet"}],
                             "en": [{"keyword": "Polymers", "type": "sujet"}]},
               "resumes": {"fr": "Résumé un", "en": "Abstract one"}},
              {"id": "s2", "titrePrincipal": "Titre deux, avec virgule"}
            ]}
            """;

    @TempDir
    static Path inputDir;

    @TempDir
    static Path outputDir;

    private static HttpServer thesesApi;

    @Autowired
    private JobOperatorTestUtils jobOperatorTestUtils;

    @Autowired
    private Job thesesDumpDataGouvJob;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        startThesesApi();
        registry.add("spring.batch.job.enabled", () -> "false");
        registry.add("app.run.timestamp", () -> RUN);
        registry.add("app.output-dir", outputDir::toString);
        registry.add("app.theses.api-base-url",
                () -> "http://localhost:" + thesesApi.getAddress().getPort() + "/api/v1");
        registry.add("app.theses.page-size", () -> "10");
        registry.add("app.datagouv.mode-test", () -> "false");
        registry.add("app.datagouv.upload-enabled", () -> "false");
        // Clés du job ANR, requises au démarrage du contexte (application.yml local non versionné)
        registry.add("app.anr.pattern", () -> TestPdfs.ANR_PATTERN);
        registry.add("app.anr.input-dir", inputDir::toString);
        registry.add("app.anr.max-files", () -> "1");
        registry.add("app.anr.offset", () -> "0");
        registry.add("app.anr.nb-pages", () -> "1");
        registry.add("app.anr.context-characters", () -> "8");
        registry.add("app.anr.chunk-size", () -> "1");
        registry.add("app.anr.exclude-keywords", () -> "annexe");
        registry.add("app.anr.exclude-dirs", () -> "");
        registry.add("app.anr.scan-threads", () -> "1");
        registry.add("app.anr.process-threads", () -> "1");
        registry.add("app.anr.fonts-dir", inputDir::toString);
        registry.add("app.anr.in-memory-max-size", () -> "1MB");
        registry.add("app.anr.read-block-size", () -> "4KB");
        registry.add("app.anr.writers.enabled", () -> "csv");
        registry.add("app.anr.writers.header", () -> "file_name,page_number,match_value,error_message,nnt");
        registry.add("app.anr.json.pretty-print", () -> "false");
        registry.add("app.solr.enabled", () -> "false");
        registry.add("app.solr.base-url", () -> "http://localhost:1/solr");
        registry.add("app.solr.id-field", () -> "id");
        registry.add("app.solr.nnt-field", () -> "nnt");
        registry.add("app.solr.doi-field", () -> "doi");
        registry.add("app.solr.defense-date-field", () -> "dateSoutenance_dt");
        registry.add("app.solr.connect-timeout", () -> "1000");
        registry.add("app.solr.read-timeout", () -> "1000");
    }

    /**
     * Faux theses.fr : une seule page de 2 thèses, inférieure à la taille de page, donc dernière page.
     */
    private static void startThesesApi() {
        try {
            thesesApi = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        thesesApi.createContext("/api/v1/theses/recherche/", exchange -> {
            byte[] body = THESES_JSON.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        thesesApi.start();
    }

    @AfterAll
    static void stopThesesApi() {
        thesesApi.stop(0);
    }

    @BeforeEach
    void selectJob() {
        jobOperatorTestUtils.setJob(thesesDumpDataGouvJob);
    }

    @Test
    void jobComplet_ecritLesTroisExports() throws Exception {
        JobExecution execution = jobOperatorTestUtils.startJob();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        StepExecution fetch = execution.getStepExecutions().iterator().next();
        assertThat(fetch.getStepName()).isEqualTo("fetchAndWriteStep");
        assertThat(fetch.getReadCount()).isEqualTo(2);
        assertThat(fetch.getWriteCount()).isEqualTo(2);
        assertThat(execution.getStepExecutions()).extracting(StepExecution::getStepName)
                .containsExactly("fetchAndWriteStep", "csvStep", "gzipStep", "uploadStep");

        List<String> csv = Files.readAllLines(outputDir.resolve(RUN + "_datagouv_results.csv"));
        assertThat(csv).hasSize(3);
        assertThat(csv.getFirst()).startsWith("﻿\"accessible\",\"auteur.idref\"");
        assertThat(csv.get(2)).contains("\"Titre deux, avec virgule\"");
        // CSV = JSONL aplati : toutes les valeurs y figurent, y compris celles sans colonne dans l'ancien format
        List<CSVRecord> records = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build()
                .parse(new StringReader(String.join("\n", csv).substring(1))).getRecords();
        assertThat(records).hasSize(2);
        CSVRecord first = records.getFirst();
        assertThat(first.get("nnt")).isEqualTo("2023MONT0001");
        assertThat(first.get("auteur.nom")).isEqualTo("Dupont");
        assertThat(first.get("auteurs.1.nom")).isEqualTo("Durand");
        assertThat(first.get("etablissements_cotutelle.0.nom")).isEqualTo("Université Laval");
        assertThat(first.get("etablissements_soutenance.0.nom")).isEqualTo("Université de Montpellier");
        assertThat(first.get("titres.es")).isEqualTo("Título uno");
        assertThat(first.get("date_soutenance")).isEqualTo("2023-06-15");
        assertThat(records.get(1).get("auteurs.1.nom")).isEmpty();

        List<String> jsonl = Files.readAllLines(outputDir.resolve(RUN + "_datagouv_results.jsonl"));
        assertThat(jsonl).hasSize(2);
        assertThat(jsonl.getFirst()).contains("\"nnt\":\"2023MONT0001\"", "\"nom\":\"ED 60\"",
                "\"nom\":\"Durand\"", "\"etablissements_cotutelle\":[{\"nom\":\"Université Laval\"",
                "\"titres\":{\"fr\":\"Titre un\",\"en\":\"Title one\",\"es\":\"Título uno\"}");
        // Sans NNT, l'identifiant de la thèse le remplace
        assertThat(jsonl.get(1)).contains("\"nnt\":\"s2\"");
        assertThat(outputDir.resolve(RUN + "_datagouv_results.jsonl.gz")).isNotEmptyFile();

        String json = Files.readString(outputDir.resolve(RUN + "_datagouv_results.json"));
        assertThat(json.strip()).startsWith("[").endsWith("]");
        assertThat(json).contains("\"Résumé un\"", "\"Titre deux, avec virgule\"");
        // Même sérialisation : chaque élément du JSON est identique à la ligne JSONL correspondante
        ObjectMapper mapper = new ObjectMapper();
        List<JsonNode> jsonlNodes = jsonl.stream().map(mapper::readTree).toList();
        assertThat(mapper.readTree(json).valueStream().toList()).isEqualTo(jsonlNodes);
    }
}

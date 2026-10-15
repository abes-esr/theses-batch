package fr.theses.batch.job;

import fr.theses.batch.support.TestPdfs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.test.JobOperatorTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exécute le job complet (reader → processors → writers CSV / JSON) sur une arborescence de PDF générés.
 * Toutes les clés app.* sont fixées ici : le test ne dépend pas du application.yml local (non versionné).
 */
@SpringBootTest
@SpringBatchTest
class ANRJobIntegrationTest {

    private static final String RUN = "2026-01-02-03-04-05";

    @TempDir
    static Path inputDir;

    @TempDir
    static Path outputDir;

    @TempDir
    static Path fontsDir;

    @Autowired
    private JobOperatorTestUtils jobOperatorTestUtils;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        createPdfs();
        registry.add("spring.batch.job.enabled", () -> "false");
        registry.add("app.run.timestamp", () -> RUN);
        registry.add("app.anr.pattern", () -> TestPdfs.ANR_PATTERN);
        registry.add("app.anr.input-dir", inputDir::toString);
        registry.add("app.anr.output-dir", outputDir::toString);
        registry.add("app.anr.max-files", () -> "100");
        registry.add("app.anr.offset", () -> "0");
        registry.add("app.anr.nb-pages", () -> "10");
        registry.add("app.anr.context-characters", () -> "8");
        registry.add("app.anr.chunk-size", () -> "2");
        registry.add("app.anr.exclude-keywords", () -> "annexe");
        registry.add("app.anr.scan-threads", () -> "2");
        registry.add("app.anr.fonts-dir", fontsDir::toString);
        registry.add("app.anr.writers.enabled", () -> "csv,json");
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

    private static void createPdfs() {
        try {
            TestPdfs.create(inputDir.resolve("THESE_1/these.pdf"), "Remerciements", "Projet ANR-15-CE23-0001 merci");
            TestPdfs.create(inputDir.resolve("THESE_1/these_annexe.pdf"), "ANR-99-EXCL-0001");
            TestPdfs.create(inputDir.resolve("THESE_2/these.pdf"), "Aucun financement");
            TestPdfs.create(inputDir.resolve("THESE_3/these.pdf"), "Labex et PIA");
            Files.createDirectories(inputDir.resolve("THESE_4"));
            Files.writeString(inputDir.resolve("THESE_4/corrompu.pdf"), "pas un pdf");
            // PDF « Word » sans /ToUnicode : le numéro n'est lisible que grâce à la police système
            TestPdfs.createType0WithoutToUnicode(inputDir.resolve("THESE_5/word.pdf"), "Projet ANR-21-WORD-0005");
            TestPdfs.installFont(fontsDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void jobComplet_ecritLesCorrespondancesEtLesErreurs() throws Exception {
        JobExecution execution = jobOperatorTestUtils.startJob();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        StepExecution step = execution.getStepExecutions().iterator().next();
        assertThat(step.getReadCount()).isEqualTo(5);
        // Le PDF sans correspondance est filtré par le processor
        assertThat(step.getFilterCount()).isEqualTo(1);
        assertThat(step.getWriteCount()).isEqualTo(4);

        List<String> csv = Files.readAllLines(outputDir.resolve(RUN + "_anr_results_unknown.csv"));
        assertThat(csv.getFirst()).isEqualTo("file_name,page_number,match_value,error_message,nnt");
        assertThat(csv.subList(1, 4)).containsExactly(
                "\"these.pdf\",2,\"ANR-15-CE23-0001\",,",
                "\"these.pdf\",1,\"Labex\",,",
                "\"these.pdf\",1,\"PIA\",,");
        // Le PDF corrompu produit une ligne d'erreur ; les lignes suivent l'ordre des chemins
        assertThat(csv).hasSize(6);
        assertThat(csv.get(4)).startsWith("\"corrompu.pdf\",,,\"Erreur lors du traitement du fichier:");
        assertThat(csv.get(5)).isEqualTo("\"word.pdf\",1,\"ANR-21-WORD-0005\",,");

        String json = Files.readString(outputDir.resolve(RUN + "_anr_results_unknown.json"));
        assertThat(json).startsWith("[{").contains("\"match_value\":\"ANR-15-CE23-0001\"").contains("corrompu.pdf");
    }
}

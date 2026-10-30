package fr.theses.batch.job.tasklet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Tests unitaires de DatagouvCsvTasklet.
 */
class DatagouvCsvTaskletTest {

    private static final String RUN = "2026-01-01-12-00-00";

    @TempDir
    Path tempDir;

    @Test
    void execute_aplatitLeJsonlAvecLUnionTrieeDesColonnes() throws IOException {
        Files.writeString(tempDir.resolve(RUN + "_datagouv_results.jsonl"), """
                {"nnt":"2020PA01","titres":{"fr":"Titre, avec virgule"},"membres_jury":[{"nom":"A"}]}
                {"nnt":"2021PA02","membres_jury":[%s]}
                """.formatted("{\"nom\":\"B\"},".repeat(10) + "{\"nom\":\"C\"}"), StandardCharsets.UTF_8);

        RepeatStatus status = new DatagouvCsvTasklet(tempDir.toString(), RUN, new ObjectMapper())
                .execute(mock(StepContribution.class), mock(ChunkContext.class));

        assertThat(status).isEqualTo(RepeatStatus.FINISHED);
        String csv = Files.readString(tempDir.resolve(RUN + "_datagouv_results.csv"), StandardCharsets.UTF_8);
        String[] lines = csv.split("\n");
        assertThat(lines).hasSize(3);
        // BOM, en-tête entre guillemets, colonnes dans l'ordre lexicographique (0, 10, 1, 2...)
        assertThat(lines[0]).isEqualTo("﻿\"membres_jury.0.nom\",\"membres_jury.1.nom\",\"membres_jury.10.nom\","
                + "\"membres_jury.2.nom\",\"membres_jury.3.nom\",\"membres_jury.4.nom\",\"membres_jury.5.nom\","
                + "\"membres_jury.6.nom\",\"membres_jury.7.nom\",\"membres_jury.8.nom\",\"membres_jury.9.nom\","
                + "\"nnt\",\"titres.fr\"");
        // Colonnes absentes d'une thèse laissées vides ; guillemets seulement si nécessaire
        assertThat(lines[1]).isEqualTo("A,,,,,,,,,,,2020PA01,\"Titre, avec virgule\"");
        assertThat(lines[2]).isEqualTo("B,B,C,B,B,B,B,B,B,B,B,2021PA02,");
    }

    @Test
    void execute_jsonlAbsent_leveException() {
        DatagouvCsvTasklet tasklet = new DatagouvCsvTasklet(tempDir.toString(), RUN, new ObjectMapper());

        assertThatThrownBy(() -> tasklet.execute(mock(StepContribution.class), mock(ChunkContext.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("_datagouv_results.jsonl");
    }
}

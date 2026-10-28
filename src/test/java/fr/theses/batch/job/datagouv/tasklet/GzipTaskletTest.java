package fr.theses.batch.job.datagouv.tasklet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Tests unitaires de GzipTasklet.
 */
class GzipTaskletTest {

    private static final String RUN = "2026-01-01-12-00-00";

    @TempDir
    Path tempDir;

    @Test
    void execute_compresseLeJsonlEnJsonlGz() throws IOException {
        String jsonl = "{\"nnt\":\"2020PA01\"}\n{\"nnt\":\"2021PA02\"}\n";
        Files.writeString(tempDir.resolve(RUN + "_datagouv_results.jsonl"), jsonl);

        RepeatStatus status = new GzipTasklet(tempDir.toString(), RUN)
                .execute(mock(StepContribution.class), mock(ChunkContext.class));

        assertThat(status).isEqualTo(RepeatStatus.FINISHED);
        try (InputStream in = new GZIPInputStream(Files.newInputStream(tempDir.resolve(RUN + "_datagouv_results.jsonl.gz")))) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(jsonl);
        }
    }

    @Test
    void execute_jsonlAbsent_leveException() {
        GzipTasklet tasklet = new GzipTasklet(tempDir.toString(), RUN);

        assertThatThrownBy(() -> tasklet.execute(mock(StepContribution.class), mock(ChunkContext.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("n'existe pas");
    }
}

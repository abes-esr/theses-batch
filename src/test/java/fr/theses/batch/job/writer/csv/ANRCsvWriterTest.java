package fr.theses.batch.job.writer.csv;

import fr.theses.batch.business.anr.model.dto.ANRMatchDTO;
import fr.theses.batch.business.anr.model.dto.ANRPageMatchDTO;
import fr.theses.batch.job.RunTimestamp;
import fr.theses.batch.job.writer.ANRResultRows;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.infrastructure.item.Chunk;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ANRCsvWriterTest {

    private static final String RUN = "2026-01-02-03-04-05";

    @TempDir
    Path outputDir;

    private ANRCsvWriter writer(String header) {
        return new ANRCsvWriter(new ANRResultRows(new RunTimestamp(RUN), outputDir.toString(), header));
    }

    private Path csv(String year) {
        return outputDir.resolve(RUN + "_anr_results_" + year + ".csv");
    }

    private static ANRMatchDTO item(String name, List<ANRPageMatchDTO> pages, String error, LocalDate date) {
        return new ANRMatchDTO("/d/" + name, name, pages, 3, 10, 1.23456, error, null, null, date);
    }

    @Test
    void ecritEnTeteEtLignesAvecEchappement() throws Exception {
        ANRPageMatchDTO page = new ANRPageMatchDTO(2, "ANR-1", "dit \"merci\"\r\nà", "");

        writer("file_name,page_number,match_value,preceding_context,following_context,processing_time")
                .write(Chunk.of(item("a.pdf", List.of(page), null, null)));

        assertThat(Files.readAllLines(csv("unknown"), StandardCharsets.UTF_8)).containsExactly(
                "file_name,page_number,match_value,preceding_context,following_context,processing_time",
                "\"a.pdf\",2,\"ANR-1\",\"dit \"\"merci\"\"  à\",,1.235");
    }

    @Test
    void ligneSansCorrespondance_celluleNumeriqueVide() throws Exception {
        writer("file_name,page_number,total_pages").write(Chunk.of(item("a.pdf", List.of(), null, null)));

        assertThat(Files.readAllLines(csv("unknown"))).containsExactly(
                "file_name,page_number,total_pages",
                "\"a.pdf\",,10");
    }

    @Test
    void chunksSuccessifsAjoutesSansRepeterLEnTete() throws Exception {
        ANRCsvWriter writer = writer("file_name");

        writer.write(Chunk.of(item("a.pdf", List.of(), null, null)));
        writer.write(Chunk.of(item("b.pdf", List.of(), null, null), item("c.pdf", List.of(), "err", null)));

        assertThat(Files.readAllLines(csv("unknown")))
                .containsExactly("file_name", "\"a.pdf\"", "\"b.pdf\"", "\"c.pdf\"");
    }

    @Test
    void unFichierParAnneeDeSoutenance() throws Exception {
        writer("file_name").write(Chunk.of(
                item("a.pdf", List.of(), null, LocalDate.of(2018, 5, 1)),
                item("b.pdf", List.of(), null, LocalDate.of(2020, 5, 1)),
                item("c.pdf", List.of(), null, null)));

        assertThat(Files.readAllLines(csv("2018"))).containsExactly("file_name", "\"a.pdf\"");
        assertThat(Files.readAllLines(csv("2020"))).containsExactly("file_name", "\"b.pdf\"");
        assertThat(Files.readAllLines(csv("unknown"))).containsExactly("file_name", "\"c.pdf\"");
    }
}

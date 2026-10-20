package fr.theses.batch.job.writer.json;

import fr.theses.batch.business.anr.model.dto.ANRMatchDTO;
import fr.theses.batch.business.anr.model.dto.ANRPageMatchDTO;
import fr.theses.batch.job.RunTimestamp;
import fr.theses.batch.job.writer.ANRResultRows;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.batch.infrastructure.item.Chunk;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ANRJsonWriterTest {

    private static final String RUN = "2026-01-02-03-04-05";

    @TempDir
    Path outputDir;

    private ANRJsonWriter writer(boolean prettyPrint) {
        ANRResultRows rows = new ANRResultRows(new RunTimestamp(RUN), outputDir.toString(),
                "file_name,page_number,match_value,processing_time");
        return new ANRJsonWriter(rows, prettyPrint);
    }

    private Path json(String year) {
        return outputDir.resolve(RUN + "_anr_results_" + year + ".json");
    }

    private static List<Map<String, Object>> read(Path file) {
        return new ObjectMapper().readValue(file.toFile(), new TypeReference<>() { });
    }

    private static ANRMatchDTO item(String name, List<ANRPageMatchDTO> pages, LocalDate date) {
        return new ANRMatchDTO("/d/" + name, name, pages, 1, 1, 0.5, null, null, null, date);
    }

    @Test
    void ecritUnTableauJsonTypeEtIndente() throws Exception {
        writer(true).write(Chunk.of(item("a.pdf", List.of(new ANRPageMatchDTO(3, "ANR-1", "", "")), null)));

        assertThat(read(json("unknown"))).containsExactly(
                Map.of("file_name", "a.pdf", "page_number", 3, "match_value", "ANR-1", "processing_time", 0.5));
        assertThat(Files.readString(json("unknown"))).contains(System.lineSeparator()).contains("  ");
    }

    @Test
    void surrogateIsole_remplaceSansFaireEchouerLEcriture() throws Exception {
        writer(false).write(Chunk.of(item("a\uD835.pdf", List.of(), null)));

        assertThat(read(json("unknown"))).singleElement()
                .satisfies(row -> assertThat(row.get("file_name")).isEqualTo("a�.pdf"));
    }

    @Test
    void sansIndentation_surUneLigne() throws Exception {
        writer(false).write(Chunk.of(item("a.pdf", List.of(), null)));

        String content = Files.readString(json("unknown"));
        assertThat(content).doesNotContain("\n").startsWith("[{");
        assertThat(read(json("unknown"))).singleElement().satisfies(row -> assertThat(row.get("page_number")).isNull());
    }

    @Test
    void chunksSuccessifsCumulesDansUnTableauValide() throws Exception {
        ANRJsonWriter writer = writer(false);

        writer.write(Chunk.of(item("a.pdf", List.of(), LocalDate.of(2019, 1, 1))));
        writer.write(Chunk.of(item("b.pdf", List.of(), LocalDate.of(2019, 6, 1)), item("c.pdf", List.of(), null)));

        assertThat(read(json("2019"))).extracting(row -> row.get("file_name")).containsExactly("a.pdf", "b.pdf");
        assertThat(read(json("unknown"))).extracting(row -> row.get("file_name")).containsExactly("c.pdf");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void ajoutsSuccessifs_identiquesAuTableauCompletEcritParJackson(boolean prettyPrint) throws Exception {
        ANRJsonWriter writer = writer(prettyPrint);
        List<ANRPageMatchDTO> twoMatches = List.of(new ANRPageMatchDTO(1, "ANR-1", "", ""), new ANRPageMatchDTO(2, "ANR-2", "", ""));

        writer.write(Chunk.of(item("a.pdf", twoMatches, null)));
        writer.write(Chunk.of(item("b.pdf", List.of(), null), item("c.pdf", twoMatches, null)));
        writer.write(Chunk.of(item("d.pdf", List.of(), null)));

        List<Map<String, Object>> rows = read(json("unknown"));
        assertThat(rows).extracting(row -> row.get("file_name"))
                .containsExactly("a.pdf", "a.pdf", "b.pdf", "c.pdf", "c.pdf", "d.pdf");
        ObjectMapper mapper = new ObjectMapper();
        String expected = prettyPrint
                ? mapper.writerWithDefaultPrettyPrinter().writeValueAsString(rows)
                : mapper.writeValueAsString(rows);
        assertThat(Files.readString(json("unknown"))).isEqualTo(expected);
    }

    @Test
    void fichierDUnRunPrecedentDeMemeNom_remplaceAuPremierChunk() throws Exception {
        Files.writeString(json("unknown"), "[{\"file_name\":\"ancien.pdf\"}]");

        writer(false).write(Chunk.of(item("a.pdf", List.of(), null)));

        assertThat(read(json("unknown"))).extracting(row -> row.get("file_name")).containsExactly("a.pdf");
    }

    @Test
    void fichierModifiePendantLeRun_erreurPlutotQuUnJsonInvalide() throws Exception {
        ANRJsonWriter writer = writer(false);
        writer.write(Chunk.of(item("a.pdf", List.of(), null)));
        Files.writeString(json("unknown"), "tronqué");

        assertThatThrownBy(() -> writer.write(Chunk.of(item("b.pdf", List.of(), null))))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("fin de tableau introuvable");
        assertThat(Files.readString(json("unknown"))).isEqualTo("tronqué");
    }
}

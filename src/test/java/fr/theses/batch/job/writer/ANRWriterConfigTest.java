package fr.theses.batch.job.writer;

import fr.theses.batch.business.anr.model.dto.ANRMatchDTO;
import fr.theses.batch.job.writer.csv.ANRCsvWriter;
import fr.theses.batch.job.writer.json.ANRJsonWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.support.CompositeItemWriter;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ANRWriterConfigTest {

    private final ANRWriterConfig config = new ANRWriterConfig();
    private ANRCsvWriter csvWriter;
    private ANRJsonWriter jsonWriter;
    private final Chunk<ANRMatchDTO> chunk = Chunk.of(
            new ANRMatchDTO("/d/a.pdf", "a.pdf", null, 0, 0, 0.0, null, null, null, null));

    @BeforeEach
    void setUp() {
        csvWriter = mock(ANRCsvWriter.class);
        jsonWriter = mock(ANRJsonWriter.class);
    }

    @Test
    void lesDeuxFormats() throws Exception {
        config.anrCompositeWriter(csvWriter, jsonWriter, new String[]{"csv", "json"}).write(chunk);

        verify(csvWriter).write(chunk);
        verify(jsonWriter).write(chunk);
    }

    @Test
    void formatRepeteBrancheUneSeuleFois_casseEtEspacesIgnores() throws Exception {
        CompositeItemWriter<ANRMatchDTO> writer =
                config.anrCompositeWriter(csvWriter, jsonWriter, new String[]{" CSV", "csv ", ""});

        writer.write(chunk);

        verify(csvWriter, times(1)).write(chunk);
        verify(jsonWriter, never()).write(chunk);
    }

    @Test
    void listeVide_echoue() {
        assertThatThrownBy(() -> config.anrCompositeWriter(csvWriter, jsonWriter, new String[]{" ", ""}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one format");
    }

    @Test
    void formatInconnu_echoue() {
        assertThatThrownBy(() -> config.anrCompositeWriter(csvWriter, jsonWriter, new String[]{"csv", "xml"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'xml'");
    }
}

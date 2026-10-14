package fr.theses.batch.job.reader;

import fr.theses.batch.business.anr.model.dto.ANRMatchDTO;
import fr.theses.batch.business.anr.service.ANRSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.infrastructure.item.NonTransientResourceException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ANRReaderTest {

    @TempDir
    Path inputDir;

    @BeforeEach
    void creerArborescence() throws IOException {
        for (String file : List.of(
                "THESE_2/b.pdf",
                "THESE_1/a.PDF",
                "THESE_1/a_annexe.pdf",
                "THESE_1/notes.txt",
                "THESE_3/sous/c.pdf",
                "racine.pdf")) {
            Path path = inputDir.resolve(file);
            Files.createDirectories(path.getParent());
            Files.writeString(path, "contenu");
        }
    }

    private ANRReader reader(int maxFiles, int offset) {
        return new ANRReader(new ANRSearchService(), inputDir.toString(), "annexe,resume", maxFiles, offset);
    }

    private static List<String> readAll(ANRReader reader) throws Exception {
        List<String> names = new ArrayList<>();
        ANRMatchDTO item;
        while ((item = reader.read()) != null) {
            names.add(item.fileName());
        }
        return names;
    }

    @Test
    void litLesPdfTriesParCheminEnExcluantMotsClesEtAutresExtensions() throws Exception {
        assertThat(readAll(reader(100, 0))).containsExactly("a.PDF", "b.pdf", "c.pdf", "racine.pdf");
    }

    @Test
    void itemInitialPorteUniquementCheminEtNom() throws Exception {
        ANRMatchDTO item = reader(100, 0).read();

        assertThat(item.filePath()).isEqualTo(inputDir.resolve("THESE_1/a.PDF").toAbsolutePath().toString());
        assertThat(item.fileName()).isEqualTo("a.PDF");
        assertThat(item.pageMatches()).isEmpty();
        assertThat(item.errorMessage()).isNull();
        assertThat(item.defenseDate()).isNull();
    }

    @Test
    void offsetSauteLesPremiersFichiers() throws Exception {
        assertThat(readAll(reader(100, 2))).containsExactly("c.pdf", "racine.pdf");
    }

    @Test
    void offsetAuDelaDeLaListe_rienALire() throws Exception {
        assertThat(readAll(reader(100, 50))).isEmpty();
    }

    @Test
    void maxFilesCompteLesFichiersSautesParLOffset() throws Exception {
        // max-files est une borne sur la position dans la liste, offset compris
        assertThat(readAll(reader(3, 1))).containsExactly("b.pdf", "c.pdf");
    }

    @Test
    void maxFilesLimiteLaLecture() throws Exception {
        assertThat(readAll(reader(2, 0))).containsExactly("a.PDF", "b.pdf");
    }

    @Test
    void repertoireAbsent_echoueDesLaConstruction() {
        String absent = inputDir.resolve("absent").toString();

        assertThatThrownBy(() -> new ANRReader(new ANRSearchService(), absent, "", 10, 0))
                .isInstanceOf(NonTransientResourceException.class)
                .hasRootCauseInstanceOf(IOException.class);
    }
}

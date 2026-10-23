package fr.theses.batch.util.file;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PdfFileScannerTest {

    @TempDir
    Path root;

    private void creer(String... files) throws IOException {
        for (String file : files) {
            Path path = root.resolve(file);
            Files.createDirectories(path.getParent());
            Files.writeString(path, "contenu");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 4})
    void trouveLesFichiersDeToutesLesProfondeurs(int threads) throws IOException {
        creer("a.pdf", "A/b.pdf", "A/notes.txt", "A/B/C/c.pdf", "D/d.pdf");

        List<Path> found = PdfFileScanner.scan(root, name -> name.endsWith(".pdf"), threads);

        assertThat(found).containsExactlyInAnyOrder(
                root.resolve("a.pdf"), root.resolve("A/b.pdf"), root.resolve("A/B/C/c.pdf"), root.resolve("D/d.pdf"));
    }

    @Test
    void repertoireDontLeNomPasseLeFiltre_parcouruMaisNonRetenu() throws IOException {
        creer("dossier.pdf/e.pdf");

        List<Path> found = PdfFileScanner.scan(root, name -> name.endsWith(".pdf"), 2);

        assertThat(found).containsExactly(root.resolve("dossier.pdf/e.pdf"));
    }

    @Test
    void racineQuiNEstPasUnRepertoire_renvoieLIOException() throws IOException {
        creer("a.pdf");

        assertThatThrownBy(() -> PdfFileScanner.scan(root.resolve("a.pdf"), name -> true, 2))
                .isInstanceOf(NotDirectoryException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 4})
    void repertoireRefuse_nEstPasParcouru(int threads) throws IOException {
        creer("ETAB/THESE_1/document/0/0/a.pdf", "ETAB/THESE_1/temp/V4/b.pdf", "ETAB/THESE_1/temp/c.pdf");
        Queue<String> tested = new ConcurrentLinkedQueue<>();

        List<Path> found = PdfFileScanner.scan(root, directory -> {
            tested.add(directory);
            return !directory.endsWith("/temp");
        }, name -> name.endsWith(".pdf"), threads);

        assertThat(found).containsExactly(root.resolve("ETAB/THESE_1/document/0/0/a.pdf"));
        // Chemins relatifs à la racine, séparés par « / » ; rien sous temp/ n'est listé
        assertThat(tested).containsExactlyInAnyOrder(
                "ETAB", "ETAB/THESE_1", "ETAB/THESE_1/document", "ETAB/THESE_1/document/0",
                "ETAB/THESE_1/document/0/0", "ETAB/THESE_1/temp");
    }

    @Test
    void filtreDeRepertoires_neConcernePasLesFichiersDeLaRacine() throws IOException {
        creer("a.pdf", "A/b.pdf");

        List<Path> found = PdfFileScanner.scan(root, directory -> false, name -> name.endsWith(".pdf"), 2);

        assertThat(found).containsExactly(root.resolve("a.pdf"));
    }
}

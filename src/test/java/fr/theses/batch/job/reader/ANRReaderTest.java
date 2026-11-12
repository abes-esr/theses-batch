package fr.theses.batch.job.reader;

import fr.theses.batch.business.anr.model.dto.ANRMatchDTO;
import fr.theses.batch.business.anr.service.ANRSearchService;
import fr.theses.batch.support.TestPdfs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
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
        return new ANRReader(new ANRSearchService(), inputDir.toString(), "annexe,resume", maxFiles, offset, 4, "", "");
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
    void totalAAtraiter_tientCompteDeLOffsetEtDeMaxFiles() {
        assertThat(reader(100, 0).getTotalToRead()).isEqualTo(4);
        assertThat(reader(100, 2).getTotalToRead()).isEqualTo(2);
        assertThat(reader(3, 1).getTotalToRead()).isEqualTo(2);
        assertThat(reader(2, 0).getTotalToRead()).isEqualTo(2);
        assertThat(reader(100, 50).getTotalToRead()).isZero();
        assertThat(reader(1, 2).getTotalToRead()).isZero();
    }

    @Test
    void totalAAtraiter_egalAuNombreDeFichiersLus() throws Exception {
        ANRReader reader = reader(3, 1);

        assertThat(readAll(reader)).hasSize(reader.getTotalToRead());
    }

    @Test
    void totalAAtraiter_avecFichierDeReprise() throws Exception {
        ANRReader reader = new ANRReader(new ANRSearchService(), inputDir.toString(), "annexe", 100, 0, 4, "b.pdf", "");

        assertThat(readAll(reader)).hasSize(reader.getTotalToRead());
    }

    @Test
    void motsClesVides_aucunPdfExclu() throws Exception {
        ANRReader reader = new ANRReader(new ANRSearchService(), inputDir.toString(), "", 100, 0, 4, "", "");

        assertThat(readAll(reader)).containsExactly("a.PDF", "a_annexe.pdf", "b.pdf", "c.pdf", "racine.pdf");
    }

    @Test
    void repertoireAbsent_echoueDesLaConstruction() {
        String absent = inputDir.resolve("absent").toString();

        assertThatThrownBy(() -> new ANRReader(new ANRSearchService(), absent, "", 10, 0, 4, "", ""))
                .isInstanceOf(NonTransientResourceException.class)
                .hasRootCauseInstanceOf(IOException.class);
    }

    /**
     * Arborescence de production : ETAB/THESE_x/document/… et ETAB/THESE_x/temp/…
     */
    @Nested
    class ExclusionDeRepertoires {

        @TempDir
        Path theses;

        @BeforeEach
        void creerTheses() throws IOException {
            TestPdfs.create(theses.resolve("ETAB/THESE_1/document/0/0/these.pdf"), "ANR-15-CE23-0001");
            TestPdfs.create(theses.resolve("ETAB/THESE_1/document/0/1/these_copie.pdf"), "ANR-15-CE23-0001");
            TestPdfs.create(theses.resolve("ETAB/THESE_1/temp/V4/these_v4.pdf"), "ANR-15-CE23-0001");
            TestPdfs.create(theses.resolve("ETAB/THESE_1/temp/pdffix_x/pdffix_x.pdf"), "ANR-15-CE23-0001");
            // Thèse sans document/ : temp/ est sa seule source
            TestPdfs.create(theses.resolve("ETAB/THESE_2/temp/seule.pdf"), "ANR-16-CE01-0002");
            // « temp » ailleurs qu'au troisième niveau n'est pas visé par */*/temp
            TestPdfs.create(theses.resolve("ETAB/THESE_3/document/temp/profond.pdf"), "Texte");
        }

        private ANRReader reader(String excludeDirs, int offset) {
            return new ANRReader(new ANRSearchService(), theses.toString(), "", 100, offset, 4, "", excludeDirs);
        }

        @Test
        void sansExclusion_litAussiTemp() throws Exception {
            // Tri sur la chaîne, sensible à la casse : « V4 » passe avant « pdffix_x »
            assertThat(readAll(reader("", 0))).containsExactly(
                    "these.pdf", "these_copie.pdf", "these_v4.pdf", "pdffix_x.pdf", "seule.pdf", "profond.pdf");
        }

        @Test
        void excluTempDeChaqueThese() throws Exception {
            assertThat(readAll(reader("*/*/temp", 0))).containsExactly("these.pdf", "these_copie.pdf", "profond.pdf");
        }

        @Test
        void theseSansDocument_disparaitEntierement() throws Exception {
            // Option retenue : temp/ est exclu partout, même quand la thèse n'a rien d'autre
            ANRReader reader = reader("*/*/temp", 0);

            assertThat(reader.getTotalToRead()).isEqualTo(3);
            assertThat(readAll(reader)).doesNotContain("seule.pdf");
        }

        @Test
        void motifSurTousLesNiveaux_excluAussiLeTempProfond() throws Exception {
            assertThat(readAll(reader("**/temp", 0))).containsExactly("these.pdf", "these_copie.pdf");
        }

        @Test
        void plusieursMotifs() throws Exception {
            assertThat(readAll(reader(" */*/temp , ETAB/THESE_3 ", 0))).containsExactly("these.pdf", "these_copie.pdf");
        }

        @Test
        void offsetSAppliqueALaListeApresExclusion() throws Exception {
            // La liste triée change : l'offset 2 d'un run sans exclusion ne désigne plus le même fichier
            assertThat(readAll(reader("", 2))).first().isEqualTo("these_v4.pdf");
            assertThat(readAll(reader("*/*/temp", 2))).containsExactly("profond.pdf");
        }

        @Test
        void motifsVides_memeListeQueSansCle() throws Exception {
            assertThat(readAll(reader(" , ", 0))).containsExactlyElementsOf(readAll(reader("", 0)));
        }
    }
}

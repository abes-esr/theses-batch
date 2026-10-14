package fr.theses.batch.job.processor;

import fr.theses.batch.business.anr.model.dto.ANRMatchDTO;
import fr.theses.batch.business.anr.model.dto.ANRPageMatchDTO;
import fr.theses.batch.support.TestPdfs;
import fr.theses.batch.util.log.PdfWarningCollector;
import fr.theses.batch.util.parser.PDFTextExtractor;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ANRProcessorTest {

    @TempDir
    Path inputDir;

    private ANRProcessor processor(PDFTextExtractor extractor, int nbPages, int contextCharacters) {
        ANRProcessor processor = new ANRProcessor(extractor);
        ReflectionTestUtils.setField(processor, "maxPages", nbPages);
        ReflectionTestUtils.setField(processor, "anrPatternSource", TestPdfs.ANR_PATTERN);
        ReflectionTestUtils.setField(processor, "contextCharacters", contextCharacters);
        ReflectionTestUtils.setField(processor, "inputDir", inputDir.toString());
        processor.init();
        return processor;
    }

    private static ANRMatchDTO item(Path file) {
        return new ANRMatchDTO(file.toString(), file.getFileName().toString(), null, 0, 0, 0.0, null, null, null, null);
    }

    @Test
    void releveChaqueCorrespondanceAvecSaPageEtSonContexte() throws Exception {
        Path pdf = TestPdfs.create(inputDir.resolve("THESE_1/these.pdf"),
                "Introduction sans financement",
                "Ce travail a ete finance par ANR-15-CE23-0001 et le Labex XYZ",
                "");

        ANRMatchDTO result = processor(new PDFTextExtractor(), 50, 10).process(item(pdf));

        assertThat(result).isNotNull();
        assertThat(result.pagesAnalyzed()).isEqualTo(3);
        assertThat(result.totalPages()).isEqualTo(3);
        assertThat(result.processingTime()).isGreaterThanOrEqualTo(0.0);
        assertThat(result.errorMessage()).isNull();
        assertThat(result.pageMatches())
                .extracting(ANRPageMatchDTO::pageNumber, ANRPageMatchDTO::matchValue)
                .containsExactly(
                        tuple(2, "ANR-15-CE23-0001"),
                        tuple(2, "Labex"));

        ANRPageMatchDTO first = result.pageMatches().getFirst();
        assertThat(first.contextBefore()).isEqualTo("nance par ");
        assertThat(first.contextAfter()).isEqualTo(" et le Lab");
    }

    @Test
    void contexteTronqueEnDebutEtFinDeTexte() throws Exception {
        Path pdf = TestPdfs.create(inputDir.resolve("court.pdf"), "PIA");

        ANRMatchDTO result = processor(new PDFTextExtractor(), 50, 100).process(item(pdf));

        ANRPageMatchDTO match = result.pageMatches().getFirst();
        assertThat(match.matchValue()).isEqualTo("PIA");
        assertThat(match.contextBefore()).isEmpty();
        assertThat(match.contextAfter()).isNotNull().hasSizeLessThan(100);
    }

    @Test
    void neLitQueNbPages() throws Exception {
        Path pdf = TestPdfs.create(inputDir.resolve("long.pdf"), "rien", "rien", "ANR-15-CE23-0001");

        assertThat(processor(new PDFTextExtractor(), 2, 10).process(item(pdf))).isNull();

        ANRMatchDTO all = processor(new PDFTextExtractor(), 0, 10).process(item(pdf));
        assertThat(all.pageMatches()).singleElement()
                .extracting(ANRPageMatchDTO::pageNumber).isEqualTo(3);
    }

    @Test
    void sansCorrespondance_renvoieNullPourNePasEcrireDeLigne() throws Exception {
        Path pdf = TestPdfs.create(inputDir.resolve("vide.pdf"), "Aucun financement ici", "");

        assertThat(processor(new PDFTextExtractor(), 50, 10).process(item(pdf))).isNull();
    }

    @Test
    void erreurDeLecture_renseigneLeMessageSansLeverDException() throws Exception {
        Path absent = inputDir.resolve("absent.pdf");

        ANRMatchDTO result = processor(new PDFTextExtractor(), 50, 10).process(item(absent));

        assertThat(result.errorMessage()).startsWith("Erreur lors du traitement du fichier: File not found");
        assertThat(result.pageMatches()).isEmpty();
    }

    @Test
    void fichierHorsInputDir_estAccepte() throws Exception {
        Path ailleurs = inputDir.getParent().resolve("hors-input-" + System.nanoTime() + ".pdf");

        ANRMatchDTO result = processor(new PDFTextExtractor(), 50, 10).process(item(ailleurs));

        assertThat(result.errorMessage()).isNotNull();
    }

    @Test
    void pageSansTexte_estIgnoree() throws Exception {
        PDFTextExtractor extractor = mock(PDFTextExtractor.class);
        when(extractor.extractTextFromPdf(anyString(), anyInt())).thenReturn(List.of(
                new PDFTextExtractor.PDFPage(1, null),
                new PDFTextExtractor.PDFPage(2, ""),
                new PDFTextExtractor.PDFPage(3, "ANR-20-ABCD")));
        when(extractor.getPageCount(anyString())).thenReturn(7);

        ANRMatchDTO result = processor(extractor, 50, 5).process(item(inputDir.resolve("x.pdf")));

        assertThat(result.pagesAnalyzed()).isEqualTo(3);
        assertThat(result.totalPages()).isEqualTo(7);
        assertThat(result.pageMatches()).singleElement()
                .extracting(ANRPageMatchDTO::pageNumber).isEqualTo(3);
    }

    @Test
    void avertissementsPdfBox_collectesPuisCollecteFermee() throws Exception {
        PDFTextExtractor extractor = mock(PDFTextExtractor.class);
        when(extractor.extractTextFromPdf(anyString(), anyInt())).thenAnswer(invocation -> {
            var pdfboxLogger = LogManager.getLogger("org.apache.pdfbox.pdmodel.font.PDSimpleFont");
            pdfboxLogger.warn("No Unicode mapping for g1 (1) in font Calibri");
            pdfboxLogger.warn("No Unicode mapping for g2 (2) in font Calibri");
            LogManager.getLogger("org.apache.fontbox.ttf.Foo").error("Bad table 12");
            throw new IOException("illisible");
        });

        ANRMatchDTO result = processor(extractor, 50, 5).process(item(inputDir.resolve("x.pdf")));

        assertThat(result.errorMessage()).endsWith("illisible");
        // La collecte a été fermée par le processor : plus rien n'est comptabilisé sur ce thread
        assertThat(PdfWarningCollector.stop()).isEmpty();
    }
}

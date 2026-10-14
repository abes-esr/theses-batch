package fr.theses.batch.util.parser;

import fr.theses.batch.support.TestPdfs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PDFTextExtractorTest {

    private final PDFTextExtractor extractor = new PDFTextExtractor();

    @TempDir
    Path tempDir;

    @Test
    void extraitLeTextePageParPage() throws IOException {
        Path pdf = TestPdfs.create(tempDir.resolve("a.pdf"), "Page un", "Page deux", "Page trois");

        List<PDFTextExtractor.PDFPage> pages = extractor.extractTextFromPdf(pdf.toString(), 0);

        assertThat(pages).extracting(PDFTextExtractor.PDFPage::getPageNumber).containsExactly(1, 2, 3);
        assertThat(pages.get(1).getText()).contains("Page deux");
    }

    @Test
    void limiteLeNombreDePagesLues() throws IOException {
        Path pdf = TestPdfs.create(tempDir.resolve("a.pdf"), "1", "2", "3");

        assertThat(extractor.extractTextFromPdf(pdf.toString(), 2)).hasSize(2);
        assertThat(extractor.extractTextFromPdf(pdf.toString(), 10)).hasSize(3);
    }

    @Test
    void getPageCount() throws IOException {
        Path pdf = TestPdfs.create(tempDir.resolve("a.pdf"), "1", "2", "3");

        assertThat(extractor.getPageCount(pdf.toString())).isEqualTo(3);
    }

    @Test
    void fichierAbsentOuNull_leveIOException() {
        String absent = tempDir.resolve("absent.pdf").toString();

        assertThatThrownBy(() -> extractor.extractTextFromPdf(absent, 0)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> extractor.extractTextFromPdf(null, 0)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> extractor.getPageCount(absent)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> extractor.getPageCount(null)).isInstanceOf(IOException.class);
    }

    @Test
    void fichierQuiNEstPasUnPdf_leveIOException() throws IOException {
        Path corrupt = Files.writeString(tempDir.resolve("corrompu.pdf"), "pas un pdf");

        assertThatThrownBy(() -> extractor.extractTextFromPdf(corrupt.toString(), 0)).isInstanceOf(IOException.class);
    }
}

package fr.theses.batch.util.parser;

import fr.theses.batch.support.TestPdfs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Extraction sans polices système (app.anr.fonts-dir vide) ; la reconstitution est couverte
 * par SystemFontUnicodeRecoveryTest
 */
class PDFTextExtractorTest {

    private final PDFTextExtractor extractor =
            new PDFTextExtractor(new SystemFontUnicodeRecovery(new SystemFontIndex()));

    @TempDir
    Path tempDir;

    @Test
    void extraitLeTextePageParPage() throws IOException {
        Path pdf = TestPdfs.create(tempDir.resolve("a.pdf"), "Page un", "Page deux", "Page trois");

        PDFExtraction extraction = extractor.extractTextFromPdf(pdf.toString(), 0);

        assertThat(extraction.pages()).extracting(PDFTextExtractor.PDFPage::pageNumber).containsExactly(1, 2, 3);
        assertThat(extraction.pages().get(1).text()).contains("Page deux");
        assertThat(extraction.totalPages()).isEqualTo(3);
    }

    @Test
    void compteLesGlyphesAffiches() throws IOException {
        Path pdf = TestPdfs.create(tempDir.resolve("a.pdf"), "abc", "de");

        PDFExtraction extraction = extractor.extractTextFromPdf(pdf.toString(), 0);

        assertThat(extraction.glyphs()).isEqualTo(5);
        assertThat(extraction.lostGlyphs()).isZero();
        assertThat(extraction.recoveredGlyphs()).isZero();
    }

    @Test
    void limiteLeNombreDePagesLuesSansChangerLeTotal() throws IOException {
        Path pdf = TestPdfs.create(tempDir.resolve("a.pdf"), "1", "2", "3");

        PDFExtraction twoPages = extractor.extractTextFromPdf(pdf.toString(), 2);
        assertThat(twoPages.pages()).hasSize(2);
        assertThat(twoPages.totalPages()).isEqualTo(3);
        assertThat(twoPages.glyphs()).isEqualTo(2);

        assertThat(extractor.extractTextFromPdf(pdf.toString(), 10).pages()).hasSize(3);
    }

    @Test
    void glyphesSansUnicode_comptesCommePerdus() throws IOException {
        Path pdf = TestPdfs.createType0WithoutToUnicode(tempDir.resolve("word.pdf"), "ANR");

        PDFExtraction extraction = extractor.extractTextFromPdf(pdf.toString(), 0);

        assertThat(extraction.glyphs()).isEqualTo(3);
        assertThat(extraction.lostGlyphs()).isEqualTo(3);
        assertThat(extraction.pages().getFirst().text()).doesNotContain("ANR");
    }

    @Test
    void glyphesNommesGNSansPolicesSysteme_comptesCommePerdus() throws IOException {
        Path pdf = TestPdfs.createGidNamed(tempDir.resolve("simple.pdf"), "ANR", (char) 0);

        PDFExtraction extraction = extractor.extractTextFromPdf(pdf.toString(), 0);

        assertThat(extraction.lostGlyphs()).isEqualTo(3);
        assertThat(extraction.lostPercent()).isEqualTo(100.0);
        assertThat(extraction.pages().getFirst().text()).doesNotContain("ANR");
    }

    @Test
    void fichierAbsentOuNull_leveIOException() {
        String absent = tempDir.resolve("absent.pdf").toString();

        assertThatThrownBy(() -> extractor.extractTextFromPdf(absent, 0))
                .isInstanceOf(IOException.class)
                .hasMessage("File not found: " + absent);
        assertThatThrownBy(() -> extractor.extractTextFromPdf(null, 0))
                .isInstanceOf(IOException.class)
                .hasMessage("File not found: null");
    }

    @Test
    void fichierQuiNEstPasUnPdf_leveIOException() throws IOException {
        Path corrupt = Files.writeString(tempDir.resolve("corrompu.pdf"), "pas un pdf");

        assertThatThrownBy(() -> extractor.extractTextFromPdf(corrupt.toString(), 0)).isInstanceOf(IOException.class);
    }
}

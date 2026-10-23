package fr.theses.batch.util.parser;

import fr.theses.batch.support.LogCapture;
import fr.theses.batch.support.TestPdfs;
import org.apache.logging.log4j.Level;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.io.RandomAccessRead;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.io.OutputStream;
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
        assertThat(extraction.fileSize()).isEqualTo(Files.size(pdf));
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
    void tableCidToGidMapDemesuree_tronqueeEtTexteExtrait() throws IOException {
        // Table identitÃ© des 65 536 CID suivie de 16 Mo de zÃ©ros : comme 2016PA066214.pdf en production,
        // dont la table dÃ©codÃ©e dÃ©passait 1 Go et Ã©puisait le heap
        byte[] map = new byte[CidToGidMapLimiter.MAX_DECODED_BYTES + (16 << 20)];
        for (int cid = 0; cid < 0x10000; cid++) {
            map[2 * cid] = (byte) (cid >> 8);
            map[2 * cid + 1] = (byte) cid;
        }
        Path pdf = TestPdfs.createType0(tempDir.resolve("table.pdf"), "ANR-15-CE23-0001", false, font -> {
            COSStream stream = new COSStream();
            try (OutputStream output = stream.createOutputStream(COSName.FLATE_DECODE)) {
                output.write(map);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            ((COSDictionary) font.getCOSArray(COSName.DESCENDANT_FONTS).getObject(0)).setItem(COSName.CID_TO_GID_MAP, stream);
        });

        try (LogCapture logs = LogCapture.of(SystemFontUnicodeRecovery.class)) {
            PDFExtraction extraction = extractor.extractTextFromPdf(pdf.toString(), 0);

            assertThat(extraction.pages().getFirst().text()).contains("ANR-15-CE23-0001");
            assertThat(logs.messages(Level.WARN)).singleElement().asString().contains("CIDToGIDMap");
        }
    }

    @Test
    void sousLeSeuil_lueEnMemoire_auDela_lueParBlocs() throws IOException {
        Path pdf = TestPdfs.create(tempDir.resolve("a.pdf"), "Page un");
        long size = Files.size(pdf);
        SystemFontUnicodeRecovery recovery = new SystemFontUnicodeRecovery(new SystemFontIndex());

        try (RandomAccessRead input = new PDFTextExtractor(recovery, DataSize.ofBytes(size)).open(pdf.toString())) {
            assertThat(input).isInstanceOf(RandomAccessReadBuffer.class);
            assertThat(input.length()).isEqualTo(size);
        }
        try (RandomAccessRead input = new PDFTextExtractor(recovery, DataSize.ofBytes(size - 1)).open(pdf.toString())) {
            assertThat(input).isInstanceOf(BlockCachedRandomAccessRead.class);
        }
        try (RandomAccessRead input = new PDFTextExtractor(recovery, DataSize.ofBytes(0)).open(pdf.toString())) {
            assertThat(input).isInstanceOf(BlockCachedRandomAccessRead.class);
        }
    }

    @Test
    void enMemoireOuParPages_memeExtraction() throws IOException {
        String[] pages = new String[400];
        for (int i = 0; i < pages.length; i++) {
            pages[i] = "Page " + i + " ANR-" + i + "-ABCD " + "x".repeat(60);
        }
        Path pdf = TestPdfs.create(tempDir.resolve("gros.pdf"), pages);
        SystemFontUnicodeRecovery recovery = new SystemFontUnicodeRecovery(new SystemFontIndex());

        PDFExtraction inMemory = new PDFTextExtractor(recovery, DataSize.ofGigabytes(1)).extractTextFromPdf(pdf.toString(), 0);
        PDFExtraction byBlocks = new PDFTextExtractor(recovery, DataSize.ofBytes(0)).extractTextFromPdf(pdf.toString(), 0);
        // Taille impaire : les objets et les lectures de PDFBox chevauchent sans cesse deux blocs
        PDFExtraction bySmallBlocks = new PDFTextExtractor(recovery, DataSize.ofBytes(0), DataSize.ofBytes(1021))
                .extractTextFromPdf(pdf.toString(), 0);

        assertThat(inMemory).isEqualTo(byBlocks).isEqualTo(bySmallBlocks);
        assertThat(inMemory.pages()).hasSize(400);
    }

    @Test
    void tailleDeBlocHorsLimites_refuseeAuDemarrage() {
        SystemFontUnicodeRecovery recovery = new SystemFontUnicodeRecovery(new SystemFontIndex());

        assertThatThrownBy(() -> new PDFTextExtractor(recovery, DataSize.ofMegabytes(64), DataSize.ofBytes(0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.anr.read-block-size");
        assertThatThrownBy(() -> new PDFTextExtractor(recovery, DataSize.ofMegabytes(64), DataSize.ofBytes((1 << 20) + 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new PDFTextExtractor(recovery, DataSize.ofMegabytes(64), DataSize.ofMegabytes(1))).isNotNull();
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

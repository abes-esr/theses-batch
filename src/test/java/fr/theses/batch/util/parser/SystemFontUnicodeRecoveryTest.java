package fr.theses.batch.util.parser;

import fr.theses.batch.support.TestPdfs;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;
import org.apache.pdfbox.pdmodel.font.encoding.WinAnsiEncoding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reconstitution du texte via une police système, de bout en bout : PDF généré → PDFTextExtractor
 * → SystemFontUnicodeRecovery → SystemFontIndex pointant sur un répertoire où LiberationSans est installée
 */
class SystemFontUnicodeRecoveryTest {

    private static final String TEXT = "ANR-15-CE23-0001";

    @TempDir
    Path tempDir;

    private Path fontsDir;
    private SystemFontUnicodeRecovery recovery;
    private PDFTextExtractor extractor;

    @BeforeEach
    void setUp() throws IOException {
        fontsDir = tempDir.resolve("fonts");
        TestPdfs.installFont(fontsDir);
        SystemFontIndex index = new SystemFontIndex();
        ReflectionTestUtils.setField(index, "fontsDir", fontsDir.toString());
        recovery = new SystemFontUnicodeRecovery(index);
        extractor = new PDFTextExtractor(recovery);
    }

    private PDFExtraction extract(Path pdf) throws IOException {
        return extractor.extractTextFromPdf(pdf.toString(), 0);
    }

    private static String text(PDFExtraction extraction) {
        return extraction.pages().getFirst().text();
    }

    /**
     * Remplace la table /ToUnicode de la police par une table n'associant que le CID donné au caractère donné
     */
    private static void replaceToUnicode(COSDictionary font, int cid, char unicode) {
        String cmap = """
                /CIDInit /ProcSet findresource begin
                12 dict begin
                begincmap
                /CMapName /Test-UCS def
                /CMapType 2 def
                1 begincodespacerange
                <0000> <FFFF>
                endcodespacerange
                1 beginbfchar
                <%04X> <%04X>
                endbfchar
                endcmap
                CMapName currentdict /CMap defineresource pop
                end
                end
                """.formatted(cid, (int) unicode);
        COSStream stream = new COSStream();
        try (OutputStream output = stream.createOutputStream()) {
            output.write(cmap.getBytes(StandardCharsets.US_ASCII));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        font.setItem(COSName.TO_UNICODE, stream);
    }

    @Test
    void policeIdentityHSansToUnicode_texteReconstitue() throws IOException {
        Path pdf = TestPdfs.createType0WithoutToUnicode(tempDir.resolve("word.pdf"), TEXT);

        PDFExtraction extraction = extract(pdf);

        assertThat(text(extraction)).contains(TEXT);
        assertThat(extraction.glyphs()).isEqualTo(TEXT.length());
        assertThat(extraction.lostGlyphs()).isZero();
        assertThat(extraction.recoveredGlyphs()).isEqualTo(TEXT.length());
    }

    @Test
    void policeDansUnFormXObject_texteReconstitue() throws IOException {
        Path pdf = TestPdfs.createType0(tempDir.resolve("form.pdf"), TEXT, true,
                font -> font.removeItem(COSName.TO_UNICODE));

        PDFExtraction extraction = extract(pdf);

        assertThat(text(extraction)).contains(TEXT);
        assertThat(extraction.recoveredGlyphs()).isEqualTo(TEXT.length());
    }

    @Test
    void toUnicodeComplet_rienAReconstituerPourLesGlyphesAffiches() throws IOException {
        Path pdf = TestPdfs.createType0(tempDir.resolve("complet.pdf"), TEXT, false, font -> { });

        PDFExtraction extraction = extract(pdf);

        assertThat(text(extraction)).contains(TEXT);
        assertThat(extraction.lostGlyphs()).isZero();
        assertThat(extraction.recoveredGlyphs()).isZero();
    }

    @Test
    void entreesToUnicodeExistantesJamaisRemplacees() throws IOException {
        // La table du PDF associe (à tort) le glyphe de « A » à « X » : elle prime sur la police système
        Path pdf = TestPdfs.createType0(tempDir.resolve("partiel.pdf"), "ANR", false,
                font -> replaceToUnicode(font, TestPdfs.glyphId('A'), 'X'));

        PDFExtraction extraction = extract(pdf);

        assertThat(text(extraction)).contains("XNR");
        assertThat(extraction.lostGlyphs()).isZero();
        assertThat(extraction.recoveredGlyphs()).isEqualTo(2);
    }

    @Test
    void toUnicodeQuiNEstPasUnFlux_policeIgnoree() throws IOException {
        Path pdf = TestPdfs.createType0(tempDir.resolve("nom.pdf"), "ANR", false,
                font -> font.setItem(COSName.TO_UNICODE, COSName.IDENTITY_H));

        assertThat(extract(pdf).recoveredGlyphs()).isZero();
    }

    @Test
    void policeSimpleAuxGlyphesNommesGN_texteReconstitue() throws IOException {
        Path pdf = TestPdfs.createGidNamed(tempDir.resolve("simple.pdf"), TEXT, (char) 0);

        PDFExtraction extraction = extract(pdf);

        assertThat(text(extraction)).contains(TEXT);
        assertThat(extraction.lostGlyphs()).isZero();
        assertThat(extraction.recoveredGlyphs()).isEqualTo(TEXT.length());
    }

    @Test
    void largeurDifferenteDeLaPoliceSysteme_glypheNonReconstitue() throws IOException {
        // « E » n'apparaît qu'une fois dans le texte : sa largeur faussée le laisse perdu, les autres sont reconstitués
        Path pdf = TestPdfs.createGidNamed(tempDir.resolve("largeur.pdf"), TEXT, 'E');

        PDFExtraction extraction = extract(pdf);

        assertThat(extraction.lostGlyphs()).isEqualTo(1);
        assertThat(extraction.recoveredGlyphs()).isEqualTo(TEXT.length() - 1);
        assertThat(text(extraction)).contains("ANR-15-C").contains("23-0001").doesNotContain("CE23");
    }

    @Test
    void policeTrouveeMaisGlyphesNommesNormalement_toUnicodeNonReecrit() throws IOException {
        Path pdf = tempDir.resolve("winansi.pdf");
        try (PDDocument document = new PDDocument(); InputStream ttf = TestPdfs.class.getResourceAsStream(
                "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
            PDTrueTypeFont font = PDTrueTypeFont.load(document, ttf, WinAnsiEncoding.INSTANCE);
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(font, 12);
                content.showText(TEXT);
                content.endText();
            }
            document.save(pdf.toFile());
        }

        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            // « A », « N »... ne sont pas des noms « gN » : aucun numéro de glyphe, rien à reconstituer
            assertThat(recovery.recover(document, 1)).isEmpty();
        }
        assertThat(text(extract(pdf))).contains(TEXT);
    }

    @Test
    void policeSansEquivalentSysteme_aucuneReconstitution() throws IOException {
        Path pdf = TestPdfs.create(tempDir.resolve("helvetica.pdf"), TEXT);

        PDFExtraction extraction = extract(pdf);

        assertThat(text(extraction)).contains(TEXT);
        assertThat(extraction.recoveredGlyphs()).isZero();
    }

    @Test
    void recover_renvoieLesCodesReconstituesParPolice() throws IOException {
        Path pdf = TestPdfs.createType0WithoutToUnicode(tempDir.resolve("word.pdf"), "AN");

        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            Map<COSDictionary, Set<Integer>> recovered = recovery.recover(document, 1);

            assertThat(recovered).hasSize(1);
            Set<Integer> codes = recovered.values().iterator().next();
            // police non sous-ensemble : tous les glyphes de la police sont couverts, pas seulement ceux affichés
            assertThat(codes).contains(TestPdfs.glyphId('A'), TestPdfs.glyphId('N'), TestPdfs.glyphId('z'));
            COSDictionary font = recovered.keySet().iterator().next();
            assertThat(font.getDictionaryObject(COSName.TO_UNICODE)).isInstanceOf(COSStream.class);
        }
    }

    @Test
    void recover_aucunePageALire_rienNEstModifie() throws IOException {
        Path pdf = TestPdfs.createType0WithoutToUnicode(tempDir.resolve("word.pdf"), "AN");

        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            assertThat(recovery.recover(document, 0)).isEmpty();
        }
    }
}

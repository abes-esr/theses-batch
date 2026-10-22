package fr.theses.batch.util.parser;

import fr.theses.batch.support.TestPdfs;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
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
import java.util.concurrent.atomic.AtomicReference;

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

    /**
     * Page dont chaque formulaire a pour /Resources le dictionnaire de ressources de la page elle-même, qui
     * les contient tous (5 859 formulaires dans NANU/THESE_215271/document/0/1/VERRON2.pdf). La page affiche
     * le texte et dessine le premier formulaire.
     */
    private Path createSharedResourcesForms(Path file, int forms) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            PDResources resources = new PDResources();
            page.setResources(resources);
            COSDictionary xobjects = new COSDictionary();
            resources.getCOSObject().setItem(COSName.XOBJECT, xobjects);
            for (int i = 0; i < forms; i++) {
                PDFormXObject form = new PDFormXObject(document);
                form.setBBox(new PDRectangle(10, 10));
                form.getCOSObject().setItem(COSName.RESOURCES, resources.getCOSObject());
                xobjects.setItem(COSName.getPDFName("Fm" + i), form);
            }
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                content.showText(TEXT);
                content.endText();
                content.drawForm((PDFormXObject) resources.getXObject(COSName.getPDFName("Fm0")));
            }
            document.save(file.toFile());
        }
        return file;
    }

    @Test
    void recover_neMetAucunFormulaireEnCache() throws IOException {
        Path pdf = createSharedResourcesForms(tempDir.resolve("formulaires.pdf"), 3);

        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            recovery.recover(document, 1);

            COSDictionary xobjects = document.getPage(0).getCOSObject()
                    .getCOSDictionary(COSName.RESOURCES).getCOSDictionary(COSName.XOBJECT);
            for (COSName name : xobjects.keySet()) {
                assertThat(document.getResourceCache().getXObject((COSObject) xobjects.getItem(name))).isNull();
            }
        }
    }

    @Test
    void milliersDeFormulairesPartageantLesRessourcesDeLaPage_pasDeDebordementDePile() throws Exception {
        // PDPage.removeResources, appelé après chaque page, retire de son cache chaque formulaire puis
        // descend dans ses ressources : un niveau de récursion par formulaire en cache. Mis en cache par
        // la reconstitution, les 5 859 formulaires de VERRON2.pdf ont fait déborder la pile en prod.
        Path pdf = createSharedResourcesForms(tempDir.resolve("formulaires.pdf"), 3000);
        AtomicReference<Object> outcome = new AtomicReference<>();
        Thread thread = new Thread(null, () -> {
            try {
                outcome.set(text(extract(pdf)));
            } catch (Throwable t) {
                outcome.set(t);
            }
        }, "pile-reduite", 256 * 1024);
        thread.start();
        thread.join();

        assertThat(outcome.get()).asString().contains(TEXT);
    }

    @Test
    void recover_aucunePageALire_rienNEstModifie() throws IOException {
        Path pdf = TestPdfs.createType0WithoutToUnicode(tempDir.resolve("word.pdf"), "AN");

        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            assertThat(recovery.recover(document, 0)).isEmpty();
        }
    }
}

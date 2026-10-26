package fr.theses.batch.job.processor;

import fr.theses.batch.business.anr.model.dto.ANRMatchDTO;
import fr.theses.batch.business.anr.model.dto.ANRPageMatchDTO;
import fr.theses.batch.job.listener.ANRRunStatistics;
import fr.theses.batch.support.LogCapture;
import fr.theses.batch.support.TestPdfs;
import fr.theses.batch.util.log.PdfWarningCollector;
import fr.theses.batch.util.parser.PDFExtraction;
import fr.theses.batch.util.parser.PDFTextExtractor;
import fr.theses.batch.util.parser.SystemFontIndex;
import fr.theses.batch.util.parser.SystemFontUnicodeRecovery;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

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
        ANRProcessor processor = new ANRProcessor(extractor, new ANRRunStatistics());
        ReflectionTestUtils.setField(processor, "maxPages", nbPages);
        ReflectionTestUtils.setField(processor, "anrPatternSource", TestPdfs.ANR_PATTERN);
        ReflectionTestUtils.setField(processor, "contextCharacters", contextCharacters);
        ReflectionTestUtils.setField(processor, "inputDir", inputDir.toString());
        // Horloge figée : durée de traitement nulle, messages reproductibles
        ReflectionTestUtils.setField(processor, "nanoClock", (LongSupplier) () -> 0L);
        processor.init();
        return processor;
    }

    /**
     * Extracteur réel, sans polices système (app.anr.fonts-dir vide)
     */
    private static PDFTextExtractor realExtractor() {
        return new PDFTextExtractor(new SystemFontUnicodeRecovery(new SystemFontIndex()));
    }

    /**
     * Processor dont l'extracteur renvoie l'extraction donnée, quel que soit le fichier
     */
    private ANRProcessor processorReturning(PDFExtraction extraction) throws IOException {
        PDFTextExtractor extractor = mock(PDFTextExtractor.class);
        when(extractor.extractTextFromPdf(anyString(), anyInt())).thenReturn(extraction);
        return processor(extractor, 50, 5);
    }

    private static PDFExtraction extraction(int glyphs, int lost, int recovered) {
        return new PDFExtraction(List.of(new PDFTextExtractor.PDFPage(1, "ANR-20-ABCD")), 1, glyphs, lost, recovered,
                3_670_016);
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

        ANRMatchDTO result = processor(realExtractor(), 50, 10).process(item(pdf));

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

        ANRMatchDTO result = processor(realExtractor(), 50, 100).process(item(pdf));

        ANRPageMatchDTO match = result.pageMatches().getFirst();
        assertThat(match.matchValue()).isEqualTo("PIA");
        assertThat(match.contextBefore()).isEmpty();
        assertThat(match.contextAfter()).isNotNull().hasSizeLessThan(100);
    }

    @Test
    void contexte_neCoupeJamaisUnePaireDeSurrogates() throws Exception {
        // 𝑥 (U+1D465) occupe deux char : la limite de 2 caractères tombe au milieu de la paire, des deux côtés
        String x = "𝑥";
        PDFExtraction extraction = new PDFExtraction(List.of(
                new PDFTextExtractor.PDFPage(1, "a" + x + " ANR-20-ABCD " + x + "a")), 1, 10, 0, 0, 0);
        PDFTextExtractor extractor = mock(PDFTextExtractor.class);
        when(extractor.extractTextFromPdf(anyString(), anyInt())).thenReturn(extraction);

        ANRPageMatchDTO match = processor(extractor, 50, 3).process(item(inputDir.resolve("x.pdf")))
                .pageMatches().getFirst();

        assertThat(match.contextBefore()).isEqualTo(x + " ");
        assertThat(match.contextAfter()).isEqualTo(" " + x);

        ANRPageMatchDTO court = processor(extractor, 50, 2).process(item(inputDir.resolve("x.pdf")))
                .pageMatches().getFirst();

        assertThat(court.contextBefore()).isEqualTo(" ");
        assertThat(court.contextAfter()).isEqualTo(" ");
    }

    @Test
    void neLitQueNbPages() throws Exception {
        Path pdf = TestPdfs.create(inputDir.resolve("long.pdf"), "rien", "rien", "ANR-15-CE23-0001");

        assertThat(processor(realExtractor(), 2, 10).process(item(pdf))).isNull();

        ANRMatchDTO all = processor(realExtractor(), 0, 10).process(item(pdf));
        assertThat(all.pageMatches()).singleElement()
                .extracting(ANRPageMatchDTO::pageNumber).isEqualTo(3);
    }

    @Test
    void sansCorrespondance_renvoieNullPourNePasEcrireDeLigne() throws Exception {
        Path pdf = TestPdfs.create(inputDir.resolve("vide.pdf"), "Aucun financement ici", "");

        assertThat(processor(realExtractor(), 50, 10).process(item(pdf))).isNull();
    }

    @Test
    void erreurDeLecture_renseigneLeMessageSansLeverDException() throws Exception {
        Path absent = inputDir.resolve("absent.pdf");

        ANRMatchDTO result = processor(realExtractor(), 50, 10).process(item(absent));

        assertThat(result.errorMessage()).startsWith("Erreur PDF: File not found");
        assertThat(result.pageMatches()).isEmpty();
    }

    @Test
    void debordementDePile_renseigneLeMessageSansFaireEchouerLeStep() throws Exception {
        // PDFBox 3.0.8 (PDPage.removeResources) récurse sans fin sur certains PDF : le StackOverflowError,
        // une Error et non une Exception, a fait échouer le step en prod après 5 h 28 min
        PDFTextExtractor extractor = mock(PDFTextExtractor.class);
        when(extractor.extractTextFromPdf(anyString(), anyInt())).thenThrow(new StackOverflowError());

        try (LogCapture logs = LogCapture.of(ANRProcessor.class)) {
            ANRMatchDTO result = processor(extractor, 50, 5).process(item(inputDir.resolve("x.pdf")));

            assertThat(result.errorMessage()).isEqualTo("Erreur PDF: débordement de pile (StackOverflowError)");
            assertThat(result.pageMatches()).isNullOrEmpty();
            assertThat(logs.messages(Level.WARN).getFirst())
                    .isEqualTo("Erreur lors du traitement du fichier x.pdf (PDF malformé) : débordement de pile (StackOverflowError)");
        }
    }

    @Test
    void pdfMalforme_messageEnWarnEtPileEnDebugSeulement() throws Exception {
        IOException erreur = new IOException("illisible");
        PDFTextExtractor extractor = mock(PDFTextExtractor.class);
        when(extractor.extractTextFromPdf(anyString(), anyInt())).thenThrow(erreur);

        try (LogCapture logs = LogCapture.of(ANRProcessor.class)) {
            processor(extractor, 50, 5).process(item(inputDir.resolve("x.pdf")));

            assertThat(logs.events(Level.WARN)).singleElement().satisfies(event -> {
                assertThat(event.getMessage().getFormattedMessage())
                        .isEqualTo("Erreur lors du traitement du fichier x.pdf (PDF malformé) : illisible");
                assertThat(event.getThrown()).isNull();
            });
            assertThat(logs.events(Level.DEBUG)).anySatisfy(event -> {
                assertThat(event.getMessage().getFormattedMessage()).isEqualTo("Pile de l'erreur pour x.pdf");
                assertThat(event.getThrown()).isSameAs(erreur);
            });
        }
    }

    @Test
    void fichierHorsInputDir_estAccepte() throws Exception {
        Path ailleurs = inputDir.getParent().resolve("hors-input-" + System.nanoTime() + ".pdf");

        ANRMatchDTO result = processor(realExtractor(), 50, 10).process(item(ailleurs));

        assertThat(result.errorMessage()).isNotNull();
    }

    @Test
    void pageSansTexte_estIgnoree() throws Exception {
        PDFExtraction extraction = new PDFExtraction(List.of(
                new PDFTextExtractor.PDFPage(1, null),
                new PDFTextExtractor.PDFPage(2, ""),
                new PDFTextExtractor.PDFPage(3, "ANR-20-ABCD")), 7, 11, 0, 0, 0);

        ANRMatchDTO result = processorReturning(extraction).process(item(inputDir.resolve("x.pdf")));

        assertThat(result.pagesAnalyzed()).isEqualTo(3);
        assertThat(result.totalPages()).isEqualTo(7);
        assertThat(result.pageMatches()).singleElement()
                .extracting(ANRPageMatchDTO::pageNumber).isEqualTo(3);
    }

    @Test
    void extractionCompleteSansAvertissement_journaliseeEnDebugSeulement() throws Exception {
        try (LogCapture logs = LogCapture.of(ANRProcessor.class)) {
            processorReturning(extraction(100, 0, 0)).process(item(inputDir.resolve("x.pdf")));

            assertThat(logs.messages(Level.WARN)).isEmpty();
            assertThat(logs.messages(Level.INFO)).isEmpty();
            assertThat(logs.messages(Level.DEBUG)).containsExactly(
                    "x.pdf (0,0 s, 3,5 Mo, 1/1 pages) : texte perdu 0,0 % (0/100 glyphes)");
        }
    }

    @Test
    void fichierLent_signaleEnInfoMemeSansAvertissement() throws Exception {
        ANRProcessor processor = processorReturning(extraction(100, 0, 0));
        // Premier appel : début du traitement ; les suivants : 12,5 s plus tard
        AtomicLong calls = new AtomicLong();
        ReflectionTestUtils.setField(processor, "nanoClock",
                (LongSupplier) () -> calls.getAndIncrement() == 0 ? 0L : 12_500_000_000L);

        try (LogCapture logs = LogCapture.of(ANRProcessor.class)) {
            ANRMatchDTO result = processor.process(item(inputDir.resolve("x.pdf")));

            assertThat(result.processingTime()).isEqualTo(12.5);
            assertThat(logs.messages(Level.INFO)).containsExactly(
                    "x.pdf (12,5 s, 3,5 Mo, 1/1 pages) : texte perdu 0,0 % (0/100 glyphes)");
        }
    }

    @Test
    void textePerdu_signaleEnWarnAvecCheminRelatif() throws Exception {
        try (LogCapture logs = LogCapture.of(ANRProcessor.class)) {
            processorReturning(extraction(200, 50, 0)).process(item(inputDir.resolve("THESE_1/x.pdf")));

            assertThat(logs.messages(Level.WARN)).containsExactly(
                    Path.of("THESE_1", "x.pdf") + " (0,0 s, 3,5 Mo, 1/1 pages) : texte perdu 25,0 % (50/200 glyphes)");
        }
    }

    @Test
    void texteEntierementReconstitue_signaleEnInfo() throws Exception {
        try (LogCapture logs = LogCapture.of(ANRProcessor.class)) {
            processorReturning(extraction(1000, 0, 1000)).process(item(inputDir.resolve("x.pdf")));

            assertThat(logs.messages(Level.WARN)).isEmpty();
            assertThat(logs.messages(Level.INFO)).containsExactly(
                    "x.pdf (0,0 s, 3,5 Mo, 1/1 pages) : texte perdu 0,0 % (0/1000 glyphes), reconstitué 100,0 % via les polices système");
        }
    }

    @Test
    void partInfimeMaisNonNulle_afficheeMoinsDeZeroVirguleUn() throws Exception {
        try (LogCapture logs = LogCapture.of(ANRProcessor.class)) {
            processorReturning(extraction(10_000, 1, 2)).process(item(inputDir.resolve("x.pdf")));

            assertThat(logs.messages(Level.INFO)).containsExactly(
                    "x.pdf (0,0 s, 3,5 Mo, 1/1 pages) : texte perdu < 0,1 % (1/10000 glyphes), reconstitué < 0,1 % via les polices système");
        }
    }

    @Test
    void avertissementsPdfBox_regroupesParTypePuisCollecteFermee() throws Exception {
        PDFTextExtractor extractor = mock(PDFTextExtractor.class);
        when(extractor.extractTextFromPdf(anyString(), anyInt())).thenAnswer(invocation -> {
            var pdfboxLogger = LogManager.getLogger("org.apache.pdfbox.pdmodel.font.PDSimpleFont");
            pdfboxLogger.warn("No Unicode mapping for g1 (1) in font Calibri");
            pdfboxLogger.warn("No Unicode mapping for g2 (2) in font Calibri");
            LogManager.getLogger("org.apache.fontbox.ttf.Foo").error("Bad table 12");
            throw new IOException("illisible");
        });

        try (LogCapture logs = LogCapture.of(ANRProcessor.class)) {
            ANRMatchDTO result = processor(extractor, 50, 5).process(item(inputDir.resolve("x.pdf")));

            assertThat(result.errorMessage()).endsWith("illisible");
            // Fichier illisible : le message d'erreur et les avertissements sont journalisés en WARN
            assertThat(logs.messages(Level.WARN)).hasSize(2);
            assertThat(logs.messages(Level.WARN).getFirst())
                    .isEqualTo("Erreur lors du traitement du fichier x.pdf (PDF malformé) : illisible");
            assertThat(logs.messages(Level.WARN).get(1))
                    .startsWith("x.pdf (0,0 s), 3 avertissement(s) PDFBox :")
                    .containsSubsequence(
                            "2 x PDSimpleFont - No Unicode mapping in font Calibri",
                            "1 x ERROR Foo - Bad table N");
        }
        // La collecte a été fermée par le processor : plus rien n'est comptabilisé sur ce thread
        assertThat(PdfWarningCollector.stop()).isEmpty();
    }

    /**
     * Processor dont l'extracteur émet des avertissements PDFBox puis renvoie l'extraction donnée
     */
    private ANRProcessor processorWarning(PDFExtraction extraction, Runnable pdfboxWarnings) throws IOException {
        PDFTextExtractor extractor = mock(PDFTextExtractor.class);
        when(extractor.extractTextFromPdf(anyString(), anyInt())).thenAnswer(invocation -> {
            pdfboxWarnings.run();
            return extraction;
        });
        return processor(extractor, 50, 5);
    }

    /**
     * Remplacements de polices simples non embarquées : 2 pour ArialMT, 1 pour Helvetica
     */
    private static void fallbackFonts() {
        var trueType = LogManager.getLogger("org.apache.pdfbox.pdmodel.font.PDTrueTypeFont");
        trueType.warn("Using fallback font LiberationSans for ArialMT");
        trueType.warn("Using fallback font LiberationSans for ArialMT");
        LogManager.getLogger("org.apache.pdfbox.pdmodel.font.PDType1Font")
                .warn("Using fallback font LiberationSans for base font Helvetica");
    }

    private static final String ARIAL_FALLBACK = "        2 x PDTrueTypeFont - Using fallback font LiberationSans for ArialMT";
    private static final String HELVETICA_FALLBACK =
            "        1 x PDType1Font - Using fallback font LiberationSans for Helvetica";

    @Test
    void avertissementsBeninsSeuls_comptesSansDetailNiWarn() throws Exception {
        try (LogCapture logs = LogCapture.of(ANRProcessor.class)) {
            processorWarning(extraction(100, 0, 0), ANRProcessorTest::fallbackFonts)
                    .process(item(inputDir.resolve("x.pdf")));

            assertThat(logs.messages(Level.WARN)).isEmpty();
            assertThat(logs.messages(Level.INFO)).isEmpty();
            assertThat(logs.messages(Level.DEBUG)).containsExactly(
                    "x.pdf (0,0 s, 3,5 Mo, 1/1 pages) : texte perdu 0,0 % (0/100 glyphes), 3 avertissement(s) PDFBox bénin(s)");
        }
    }

    @Test
    void avertissementsBeninsEtTexteReconstitue_resteEnInfo() throws Exception {
        try (LogCapture logs = LogCapture.of(ANRProcessor.class)) {
            processorWarning(extraction(100, 0, 100), ANRProcessorTest::fallbackFonts)
                    .process(item(inputDir.resolve("x.pdf")));

            assertThat(logs.messages(Level.WARN)).isEmpty();
            assertThat(logs.messages(Level.INFO)).singleElement().asString()
                    .isEqualTo("x.pdf (0,0 s, 3,5 Mo, 1/1 pages) : texte perdu 0,0 % (0/100 glyphes), reconstitué 100,0 % via les polices système, 3 avertissement(s) PDFBox bénin(s)");
        }
    }

    @Test
    void avertissementsBeninsEtUnNonBenin_signaleEnWarnNonBeninEnTete() throws Exception {
        try (LogCapture logs = LogCapture.of(ANRProcessor.class)) {
            processorWarning(extraction(100, 0, 0), () -> {
                fallbackFonts();
                // Police CID : PDFBox peut lire les caractères dans la cmap de la police de remplacement
                LogManager.getLogger("org.apache.pdfbox.pdmodel.font.PDCIDFontType2")
                        .warn("Using fallback font LiberationSans for CID-keyed TrueType font Calibri");
            }).process(item(inputDir.resolve("x.pdf")));

            assertThat(logs.messages(Level.WARN)).containsExactly(String.join(System.lineSeparator(),
                    "x.pdf (0,0 s, 3,5 Mo, 1/1 pages) : texte perdu 0,0 % (0/100 glyphes), 1 avertissement(s) PDFBox (+ 3 bénin(s)) :",
                    "        1 x PDCIDFontType2 - Using fallback font LiberationSans for CID-keyed TrueType font Calibri",
                    ARIAL_FALLBACK,
                    HELVETICA_FALLBACK));
        }
    }

    @Test
    void avertissementsBeninsEtTextePerdu_signaleEnWarn() throws Exception {
        try (LogCapture logs = LogCapture.of(ANRProcessor.class)) {
            processorWarning(extraction(200, 50, 0), ANRProcessorTest::fallbackFonts)
                    .process(item(inputDir.resolve("x.pdf")));

            assertThat(logs.messages(Level.WARN)).containsExactly(
                    "x.pdf (0,0 s, 3,5 Mo, 1/1 pages) : texte perdu 25,0 % (50/200 glyphes), 3 avertissement(s) PDFBox bénin(s)");
        }
    }

    /**
     * Glyphes sans Unicode dans trois polices de symboles, dont une déclinée en -Identity-H
     */
    private static void symbolFontsWithoutUnicode() {
        var simple = LogManager.getLogger("org.apache.pdfbox.pdmodel.font.PDSimpleFont");
        simple.warn("No Unicode mapping for g1 (1) in font ABCDEF+CMSY10");
        simple.warn("No Unicode mapping for g2 (2) in font ABCDEF+CMSY10");
        simple.warn("No Unicode mapping for g3 (3) in font GHIJKL+MSAM10");
        var type0 = LogManager.getLogger("org.apache.pdfbox.pdmodel.font.PDType0Font");
        type0.warn("No Unicode mapping for CID+4 (4) in font MNOPQR+Cambria#20Math");
        type0.warn("No Unicode mapping for CID+5 (5) in font MNOPQR+Cambria#20Math-Identity-H");
    }

    @Test
    void textePerduSousLeSeuil_signaleEnInfoAvecUneLigneParClasseDePolice() throws Exception {
        try (LogCapture logs = LogCapture.of(ANRProcessor.class)) {
            processorWarning(extraction(1000, 9, 0), ANRProcessorTest::symbolFontsWithoutUnicode)
                    .process(item(inputDir.resolve("x.pdf")));

            assertThat(logs.messages(Level.WARN)).isEmpty();
            assertThat(logs.messages(Level.INFO)).containsExactly(String.join(System.lineSeparator(),
                    "x.pdf (0,0 s, 3,5 Mo, 1/1 pages) : texte perdu 0,9 % (9/1000 glyphes), 5 avertissement(s) PDFBox :",
                    "        3 x PDSimpleFont - No Unicode mapping in font CMSY10 (2), MSAM10 (1)",
                    "        2 x PDType0Font - No Unicode mapping in font Cambria Math"));
        }
    }

    @Test
    void textePerduAuSeuil_signaleEnWarn() throws Exception {
        try (LogCapture logs = LogCapture.of(ANRProcessor.class)) {
            processorWarning(extraction(1000, 10, 0), ANRProcessorTest::symbolFontsWithoutUnicode)
                    .process(item(inputDir.resolve("x.pdf")));

            assertThat(logs.messages(Level.WARN)).singleElement().asString()
                    .startsWith("x.pdf (0,0 s, 3,5 Mo, 1/1 pages) : texte perdu 1,0 % (10/1000 glyphes), 5 avertissement(s) PDFBox :");
        }
    }

    @Test
    void avertissementNonBeninSansTextePerdu_signaleEnWarn() throws Exception {
        try (LogCapture logs = LogCapture.of(ANRProcessor.class)) {
            processorWarning(extraction(1000, 0, 0), () -> {
                // ToUnicode remplacée par Identity : texte possiblement faux sans aucun glyphe perdu
                var font = LogManager.getLogger("org.apache.pdfbox.pdmodel.font.PDFont");
                font.warn("Invalid ToUnicode CMap in font ABCDEF+Calibri");
                font.warn("Using predefined identity CMap instead");
            }).process(item(inputDir.resolve("x.pdf")));

            assertThat(logs.messages(Level.WARN)).containsExactly(String.join(System.lineSeparator(),
                    "x.pdf (0,0 s, 3,5 Mo, 1/1 pages) : texte perdu 0,0 % (0/1000 glyphes), 2 avertissement(s) PDFBox :",
                    "        1 x PDFont - Invalid ToUnicode CMap in font ABCDEF+Calibri",
                    "        1 x PDFont - Using predefined identity CMap instead"));
        }
    }
}

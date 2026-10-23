package fr.theses.batch.util.parser;

import fr.theses.batch.support.TestPdfs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class SystemFontIndexTest {

    @TempDir
    Path fontsDir;

    private static SystemFontIndex index(String fontsDir) {
        SystemFontIndex index = new SystemFontIndex();
        ReflectionTestUtils.setField(index, "fontsDir", fontsDir);
        return index;
    }

    @ParameterizedTest
    @CsvSource({
            "ABCDEF+TimesNewRomanPSMT, timesnewroman",
            "'TimesNewRoman,Bold', timesnewroman|bold",
            "'ABCDEF+TimesNewRoman,BoldItalic', timesnewroman|bold|italic",
            "Calibri-Bold-Identity-H, calibri|bold",
            "Calibri-Identity-H, calibri",
            "ArialMT, arial",
            "Arial-BoldItalicMT, arial|bold|italic",
            "Helvetica-Oblique, helvetica|italic",
            "Segoe UI, segoeui",
            "LiberationSans, liberationsans"
    })
    void keyOfPdfFont_familleGraisseEtStyle(String pdfFontName, String expected) {
        assertThat(SystemFontIndex.keyOfPdfFont(pdfFontName)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "ABCDEF+", "+++", "-Bold"})
    void keyOfPdfFont_nomSansFamille(String pdfFontName) {
        assertThat(SystemFontIndex.keyOfPdfFont(pdfFontName)).isNull();
    }

    @Test
    void find_retrouveLaPoliceInstalleeEtTraduitSesGlyphes() throws IOException {
        TestPdfs.installFont(fontsDir);
        int gidOfA = TestPdfs.glyphId('A');

        SystemFontIndex.SystemFont font = index(fontsDir.toString()).find("ABCDEF+LiberationSans").orElseThrow();

        assertThat(font.name()).isEqualTo(TestPdfs.FONT_NAME);
        assertThat(font.numberOfGlyphs()).isGreaterThan(gidOfA);
        assertThat(font).hasToString("SystemFont[" + TestPdfs.FONT_NAME + ", " + font.numberOfGlyphs() + " glyphes]");
        assertThat(font.unicodeOf(gidOfA)).isEqualTo("A");
        // .notdef (glyphe 0) n'a pas de caractère
        assertThat(font.unicodeOf(0)).isNull();
        // largeurs ramenées à 1000 unités par em, comme dans un PDF : « A » fait 667 en Helvetica / Liberation Sans
        assertThat(font.widthOf(gidOfA)).isCloseTo(667f, org.assertj.core.data.Offset.offset(1f));
    }

    @Test
    void find_chargeChaquePoliceUneSeuleFois() throws IOException {
        TestPdfs.installFont(fontsDir);
        SystemFontIndex index = index(fontsDir.toString());

        Optional<SystemFontIndex.SystemFont> first = index.find("LiberationSans");
        Optional<SystemFontIndex.SystemFont> second = index.find("ABCDEF+LiberationSans-Identity-H");

        assertThat(first).isPresent();
        assertThat(second).isSameAs(first);
    }

    @Test
    void find_appelsConcurrents_uneSeulePoliceChargeeEtLisible() throws Exception {
        TestPdfs.installFont(fontsDir);
        SystemFontIndex index = index(fontsDir.toString());
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Optional<SystemFontIndex.SystemFont>>> results = new ArrayList<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return index.find("ABCDEF+LiberationSans-Identity-H");
                }));
            }
            start.countDown();

            Optional<SystemFontIndex.SystemFont> first = results.getFirst().get();
            assertThat(first).isPresent();
            for (Future<Optional<SystemFontIndex.SystemFont>> result : results) {
                assertThat(result.get()).isSameAs(first);
            }
            // la police a été recopiée puis refermée : elle reste lisible
            assertThat(first.get().unicodeOf(TestPdfs.glyphId('A'))).isEqualTo("A");
            assertThat(first.get().unicodeOf(-1)).isNull();
            assertThat(first.get().unicodeOf(first.get().numberOfGlyphs())).isNull();
        }
    }

    @Test
    void find_grasseOuStyleAbsents_aucunePolice() throws IOException {
        TestPdfs.installFont(fontsDir);
        SystemFontIndex index = index(fontsDir.toString());

        assertThat(index.find("LiberationSans,Bold")).isEmpty();
        assertThat(index.find("LiberationSans-Italic")).isEmpty();
        assertThat(index.find("Calibri")).isEmpty();
        assertThat(index.find(null)).isEmpty();
    }

    @Test
    void find_retrouveUnePoliceDansUneCollectionTtc() throws IOException {
        TestPdfs.installFontCollection(fontsDir);

        SystemFontIndex.SystemFont font = index(fontsDir.toString()).find("LiberationSans").orElseThrow();

        assertThat(font.name()).isEqualTo(TestPdfs.FONT_NAME);
        assertThat(font.unicodeOf(TestPdfs.glyphId('Z'))).isEqualTo("Z");
    }

    @Test
    void parcourtLesSousRepertoiresEtIgnoreLesFichiersInvalides() throws IOException {
        TestPdfs.installFont(fontsDir.resolve("truetype/liberation"));
        Files.writeString(fontsDir.resolve("corrompue.ttf"), "pas une police");
        Files.writeString(fontsDir.resolve("corrompue.ttc"), "pas une collection");
        Files.writeString(fontsDir.resolve("lisezmoi.txt"), "texte");

        assertThat(index(fontsDir.toString()).find("LiberationSans")).isPresent();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "  ")
    void sansRepertoireDePolices_reconstitutionDesactivee(String dir) {
        assertThat(index(dir).find("LiberationSans")).isEmpty();
    }

    @Test
    void repertoireDePolicesIntrouvable_aucunePolice() {
        assertThat(index(fontsDir.resolve("absent").toString()).find("LiberationSans")).isEmpty();
    }
}

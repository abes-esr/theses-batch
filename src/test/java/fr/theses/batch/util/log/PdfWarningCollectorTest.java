package fr.theses.batch.util.log;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.SimpleMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PdfWarningCollectorTest {

    @AfterEach
    void fermerLaCollecte() {
        PdfWarningCollector.stop();
    }

    private static LogEvent event(String loggerName, Level level, String message) {
        return Log4jLogEvent.newBuilder()
                .setLoggerName(loggerName)
                .setLevel(level)
                .setMessage(new SimpleMessage(message))
                .build();
    }

    @Test
    void horsCollecte_lEvenementNestPasComptabilise() {
        assertThat(PdfWarningCollector.collect(event("org.apache.pdfbox.Foo", Level.WARN, "x"))).isFalse();
        assertThat(PdfWarningCollector.stop()).isEmpty();
    }

    @Test
    void enCollecte_regroupeParTypeNormalise() {
        PdfWarningCollector.start();

        String font = "org.apache.pdfbox.pdmodel.font.PDSimpleFont";
        PdfWarningCollector.collect(event(font, Level.WARN, "No Unicode mapping for g12 (12) in font ABCDEF+Calibri"));
        PdfWarningCollector.collect(event(font, Level.WARN, "No Unicode mapping for g7 (7) in font ABCDEF+Calibri"));
        PdfWarningCollector.collect(event("org.apache.fontbox.cff.Parser", Level.ERROR, "Bad offset 123 at 45"));
        PdfWarningCollector.collect(event("org.apache.fontbox.cff.Parser", Level.ERROR, "Bad offset 9 at 1"));

        Map<String, Integer> counts = PdfWarningCollector.stop();

        assertThat(counts).containsExactly(
                Map.entry("WARN PDSimpleFont - No Unicode mapping in font Calibri", 2),
                Map.entry("ERROR Parser - Bad offset N at N", 2));
    }

    @Test
    void stop_fermeLaCollecte() {
        PdfWarningCollector.start();
        PdfWarningCollector.stop();

        assertThat(PdfWarningCollector.collect(event("org.apache.pdfbox.Foo", Level.WARN, "x"))).isFalse();
    }

    @Test
    void filtre_rejetteEnCollecteEtLaissePasserSinon() {
        PdfWarningFilter filter = PdfWarningFilter.createFilter();
        LogEvent event = event("org.apache.pdfbox.Foo", Level.WARN, "x");

        assertThat(filter.filter(event)).isEqualTo(Filter.Result.NEUTRAL);

        PdfWarningCollector.start();
        assertThat(filter.filter(event)).isEqualTo(Filter.Result.DENY);
        assertThat(PdfWarningCollector.stop()).containsEntry("WARN Foo - x", 1);
    }

    @Test
    void nomsDePolice_normalisesPourRegrouperLesDeclinaisons() {
        PdfWarningCollector.start();

        String type0 = "org.apache.pdfbox.pdmodel.font.PDType0Font";
        PdfWarningCollector.collect(event(type0, Level.WARN, "No Unicode mapping for CID+1 (1) in font ABCDEF+Cambria#20Math"));
        PdfWarningCollector.collect(event(type0, Level.WARN,
                "No Unicode mapping for CID+2 (2) in font GHIJKL+Cambria#2320Math-Identity-H-Identity-H"));
        PdfWarningCollector.collect(event("org.apache.pdfbox.pdmodel.font.PDType1Font", Level.WARN,
                "Using fallback font LiberationSans for base font Helvetica"));
        PdfWarningCollector.collect(event("org.apache.pdfbox.pdmodel.font.PDCIDFontType2", Level.WARN,
                "Using fallback font LiberationSans for CID-keyed TrueType font ABCDEF+Calibri"));
        PdfWarningCollector.collect(event("org.apache.fontbox.ttf.PostScriptTable", Level.WARN,
                "No PostScript name data is provided for the font CMSY10"));

        assertThat(PdfWarningCollector.stop()).containsExactly(
                Map.entry("WARN PDType0Font - No Unicode mapping in font Cambria Math", 2),
                Map.entry("WARN PDType1Font - Using fallback font LiberationSans for Helvetica", 1),
                Map.entry("WARN PDCIDFontType2 - Using fallback font LiberationSans for CID-keyed TrueType font Calibri", 1),
                Map.entry("WARN PostScriptTable - No PostScript name data is provided for the font CMSY10", 1));
    }

    @Test
    void nomDePolice_echappementsNonImprimablesConserves() {
        assertThat(PdfWarningCollector.fontName("#cb#ce#cc#e5-Identity-H")).isEqualTo("#cb#ce#cc#e5");
        assertThat(PdfWarningCollector.fontName("-Identity-H")).isEqualTo("-Identity-H");
    }

    @Test
    void record_comptabiliseSeulementEnCollecte() {
        assertThat(PdfWarningCollector.record("WARN Foo - x")).isFalse();

        PdfWarningCollector.start();
        assertThat(PdfWarningCollector.record("WARN Foo - x")).isTrue();
        assertThat(PdfWarningCollector.record("WARN Foo - x")).isTrue();
        assertThat(PdfWarningCollector.stop()).containsExactly(Map.entry("WARN Foo - x", 2));
    }
}

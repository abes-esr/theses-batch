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
                Map.entry("WARN PDSimpleFont - No Unicode mapping in font ABCDEF+Calibri", 2),
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
}

package fr.theses.batch.util.log;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PdfWarningSummaryTest {

    @Test
    void typesNeDifferantQueParLaPolice_surUneLigneTrieeParTotal() {
        List<String> lines = PdfWarningSummary.lines(List.of(
                Map.entry("WARN PDFont - Using predefined identity CMap instead", 4),
                Map.entry("WARN PDSimpleFont - No Unicode mapping in font CMSY10", 3),
                Map.entry("WARN PDSimpleFont - No Unicode mapping in font MSAM10", 2),
                Map.entry("ERROR PDType1Font - Can't read the embedded Type1 font F1", 1)));

        assertThat(lines).containsExactly(
                "    5 x PDSimpleFont - No Unicode mapping in font CMSY10 (3), MSAM10 (2)",
                "    4 x PDFont - Using predefined identity CMap instead",
                "    1 x ERROR PDType1Font - Can't read the embedded Type1 font F1");
    }

    @Test
    void auDelaDeCinqPolices_lesSuivantesSontDenombrees() {
        List<String> lines = PdfWarningSummary.lines(List.of(
                Map.entry("WARN PDTrueTypeFont - Using fallback font LiberationSans for A", 7),
                Map.entry("WARN PDTrueTypeFont - Using fallback font LiberationSans for B", 6),
                Map.entry("WARN PDTrueTypeFont - Using fallback font LiberationSans for C", 5),
                Map.entry("WARN PDTrueTypeFont - Using fallback font LiberationSans for D", 4),
                Map.entry("WARN PDTrueTypeFont - Using fallback font LiberationSans for E", 3),
                Map.entry("WARN PDTrueTypeFont - Using fallback font LiberationSans for F", 2),
                Map.entry("WARN PDTrueTypeFont - Using fallback font LiberationSans for G", 1)));

        assertThat(lines).containsExactly("   28 x PDTrueTypeFont - Using fallback font LiberationSans for "
                + "A (7), B (6), C (5), D (4), E (3) et 2 autres");
    }

    @Test
    void remplacementDePoliceCid_groupeAPart() {
        List<String> lines = PdfWarningSummary.lines(List.of(
                Map.entry("WARN PDCIDFontType2 - Using fallback font LiberationSans for CID-keyed TrueType font Arial", 2),
                Map.entry("WARN PDCIDFontType2 - Using fallback font LiberationSans for CID-keyed TrueType font Calibri", 1)));

        assertThat(lines).containsExactly(
                "    3 x PDCIDFontType2 - Using fallback font LiberationSans for CID-keyed TrueType font Arial (2), Calibri (1)");
    }
}

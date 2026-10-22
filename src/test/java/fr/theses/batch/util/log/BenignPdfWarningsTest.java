package fr.theses.batch.util.log;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class BenignPdfWarningsTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "WARN PDTrueTypeFont - Using fallback font LiberationSans for ArialMT",
            "WARN PDType1Font - Using fallback font LiberationSans for base font Helvetica",
            "WARN PDType1CFont - Using fallback font LiberationSans for ABCDEF+MinionPro"})
    void remplacementDePoliceSimple_estBenin(String type) {
        assertThat(BenignPdfWarnings.isBenign(type)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // Police CID : sans /ToUnicode, le texte peut venir de la cmap de la police de remplacement
            "WARN PDCIDFontType2 - Using fallback font LiberationSans for CID-keyed TrueType font Calibri",
            "ERROR PDTrueTypeFont - Using fallback font LiberationSans for ArialMT",
            "WARN PDTrueTypeFont - Could not read embedded TTF for font ABCDEF+Calibri",
            "WARN PDSimpleFont - No Unicode mapping in font ABCDEF+Calibri"})
    void autresAvertissements_neSontPasBenins(String type) {
        assertThat(BenignPdfWarnings.isBenign(type)).isFalse();
    }
}

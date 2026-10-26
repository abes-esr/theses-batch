package fr.theses.batch.util.log;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class BenignPdfWarningsTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "WARN PDTrueTypeFont - Using fallback font LiberationSans for ArialMT",
            "WARN PDType1Font - Using fallback font LiberationSans for base font Helvetica",
            "WARN PDType1CFont - Using fallback font LiberationSans for ABCDEF+MinionPro",
            "WARN PostScriptTable - No PostScript name data is provided for the font TimesNewRomanPSMT",
            "WARN KerningSubtable - Kerning sub-table too short, got N bytes, expect N or more.",
            "WARN Type1CharString - closepath without initial moveTo in font CMR10, glyph .notdef",
            "WARN DictionaryEncoding - /BaseEncoding in type N font",
            "WARN PDFStreamParser - ignoring 'EI' assumed to be in the middle of inline image at stream offset N, s = 'x'",
            "ERROR SetGraphicsStateParameters - name for 'gs' operator not found in resources: /pgf@caN.N",
            "WARN COSParser - /XRefStm offset N is incorrect, corrected to N",
            "WARN SystemFontUnicodeRecovery - Table /CIDToGIDMap démesurée tronquée, police CALS_InvisibleTTFont"})
    void avertissementsSansEffetSurLeTexte_sontBenins(String type) {
        assertThat(BenignPdfWarnings.isBenign(type)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // Police CID : sans /ToUnicode, le texte peut venir de la cmap de la police de remplacement
            "WARN PDCIDFontType2 - Using fallback font LiberationSans for CID-keyed TrueType font Calibri",
            "ERROR PDTrueTypeFont - Using fallback font LiberationSans for ArialMT",
            "WARN PDTrueTypeFont - Could not read embedded TTF for font ABCDEF+Calibri",
            "WARN PDSimpleFont - No Unicode mapping in font ABCDEF+Calibri",
            // Identity substituée à une /ToUnicode invalide : texte possiblement faux
            "WARN PDFont - Invalid ToUnicode CMap in font Calibri",
            "WARN PDFont - Using predefined identity CMap instead",
            "ERROR COSParser - Failed to parse /XRefStm at offset N"})
    void autresAvertissements_neSontPasBenins(String type) {
        assertThat(BenignPdfWarnings.isBenign(type)).isFalse();
    }

    @Test
    void glypheSansUnicode_dejaMesureParLeTextePerdu() {
        assertThat(BenignPdfWarnings.isMeasuredByLostText("WARN PDType0Font - No Unicode mapping in font Cambria Math")).isTrue();
        assertThat(BenignPdfWarnings.isBenign("WARN PDType0Font - No Unicode mapping in font Cambria Math")).isFalse();
        assertThat(BenignPdfWarnings.isMeasuredByLostText("WARN PDFont - Invalid ToUnicode CMap in font Calibri")).isFalse();
    }
}

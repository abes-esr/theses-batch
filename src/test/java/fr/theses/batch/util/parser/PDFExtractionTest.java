package fr.theses.batch.util.parser;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PDFExtractionTest {

    @Test
    void partsEnPourcentageDesGlyphesAffiches() {
        PDFExtraction extraction = new PDFExtraction(List.of(), 3, 200, 50, 30, 1024);

        assertThat(extraction.lostPercent()).isEqualTo(25.0);
        assertThat(extraction.recoveredPercent()).isEqualTo(15.0);
    }

    @Test
    void aucunGlyphe_partsNulles() {
        PDFExtraction extraction = new PDFExtraction(List.of(), 1, 0, 0, 0, 0);

        assertThat(extraction.lostPercent()).isZero();
        assertThat(extraction.recoveredPercent()).isZero();
    }
}

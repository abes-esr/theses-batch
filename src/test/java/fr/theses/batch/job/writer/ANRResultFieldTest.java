package fr.theses.batch.job.writer;

import fr.theses.batch.business.anr.model.dto.ANRMatchDTO;
import fr.theses.batch.business.anr.model.dto.ANRPageMatchDTO;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ANRResultFieldTest {

    private static final ANRPageMatchDTO PAGE = new ANRPageMatchDTO(4, "ANR-15-CE23-0001", "avant", "après");
    private static final ANRMatchDTO COMPLET = new ANRMatchDTO("/d/THESE_1/a.pdf", "a.pdf", List.of(PAGE),
            10, 120, 1.5, "erreur", "2018PA01", "10.1/x", LocalDate.of(2018, 12, 12));
    private static final ANRMatchDTO VIDE = new ANRMatchDTO("/d/a.pdf", "a.pdf", null,
            0, 0, 0.0, null, null, null, null);

    @Test
    void ligneDeCorrespondance() {
        assertThat(ANRResultField.FILE_PATH.extract(COMPLET, PAGE)).isEqualTo("/d/THESE_1/a.pdf");
        assertThat(ANRResultField.FILE_NAME.extract(COMPLET, PAGE)).isEqualTo("a.pdf");
        assertThat(ANRResultField.PAGE_NUMBER.extract(COMPLET, PAGE)).isEqualTo(4);
        assertThat(ANRResultField.MATCH_VALUE.extract(COMPLET, PAGE)).isEqualTo("ANR-15-CE23-0001");
        assertThat(ANRResultField.PRECEDING_CONTEXT.extract(COMPLET, PAGE)).isEqualTo("avant");
        assertThat(ANRResultField.FOLLOWING_CONTEXT.extract(COMPLET, PAGE)).isEqualTo("après");
        assertThat(ANRResultField.ANALYZED_PAGES_NUMBER.extract(COMPLET, PAGE)).isEqualTo(10);
        assertThat(ANRResultField.TOTAL_PAGES.extract(COMPLET, PAGE)).isEqualTo(120);
        assertThat(ANRResultField.PROCESSING_TIME.extract(COMPLET, PAGE)).isEqualTo(1.5);
        // Le message d'erreur n'est porté que par la ligne sans correspondance
        assertThat(ANRResultField.ERROR_MESSAGE.extract(COMPLET, PAGE)).isEqualTo("");
        assertThat(ANRResultField.NNT.extract(COMPLET, PAGE)).isEqualTo("2018PA01");
        assertThat(ANRResultField.DOI.extract(COMPLET, PAGE)).isEqualTo("10.1/x");
        assertThat(ANRResultField.DEFENSE_DATE.extract(COMPLET, PAGE)).isEqualTo("2018-12-12");
    }

    @Test
    void ligneSansCorrespondance() {
        assertThat(ANRResultField.PAGE_NUMBER.extract(COMPLET, null)).isNull();
        assertThat(ANRResultField.MATCH_VALUE.extract(COMPLET, null)).isEqualTo("");
        assertThat(ANRResultField.PRECEDING_CONTEXT.extract(COMPLET, null)).isEqualTo("");
        assertThat(ANRResultField.FOLLOWING_CONTEXT.extract(COMPLET, null)).isEqualTo("");
        assertThat(ANRResultField.ERROR_MESSAGE.extract(COMPLET, null)).isEqualTo("erreur");
    }

    @Test
    void valeursAbsentesRenduesVides() {
        assertThat(ANRResultField.ERROR_MESSAGE.extract(VIDE, null)).isEqualTo("");
        assertThat(ANRResultField.NNT.extract(VIDE, null)).isEqualTo("");
        assertThat(ANRResultField.DOI.extract(VIDE, null)).isEqualTo("");
        assertThat(ANRResultField.DEFENSE_DATE.extract(VIDE, null)).isEqualTo("");
    }

    @Test
    void fromName_ignoreLaCasse() {
        assertThat(ANRResultField.fromName("Match_Value")).isEqualTo(ANRResultField.MATCH_VALUE);
        assertThat(ANRResultField.fromName("doi").fieldName()).isEqualTo("doi");
    }

    @Test
    void fromName_nomInconnu() {
        assertThatThrownBy(() -> ANRResultField.fromName("inconnu"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inconnu");
    }
}

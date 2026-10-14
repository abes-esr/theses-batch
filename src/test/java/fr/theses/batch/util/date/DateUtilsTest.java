package fr.theses.batch.util.date;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class DateUtilsTest {

    private static final LocalDateTime DATE = LocalDateTime.of(2024, 3, 7, 9, 5, 2);

    @Test
    void format_affichage() {
        assertThat(DateUtils.format(DATE)).isEqualTo("2024-03-07 09:05:02");
    }

    @Test
    void formatForFileName_sansEspaceNiDeuxPoints() {
        assertThat(DateUtils.formatForFileName(DATE)).isEqualTo("2024-03-07-09-05-02");
    }

    @Test
    void dateCouranteAuxDeuxFormats() {
        assertThat(DateUtils.getCurrentDateTime()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
        assertThat(DateUtils.getCurrentDateTimeForFileName()).matches("\\d{4}(-\\d{2}){5}");
    }
}

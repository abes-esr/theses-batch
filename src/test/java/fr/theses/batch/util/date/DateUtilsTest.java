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
    void formatDuration_enMsSousLaSeconde_alaSecondeSousLHeure_alaMinuteAuDela() {
        long second = 1_000_000_000L;
        assertThat(DateUtils.formatDuration(0)).isEqualTo("0 s");
        assertThat(DateUtils.formatDuration(-second)).isEqualTo("0 s");
        assertThat(DateUtils.formatDuration(850_000_000L)).isEqualTo("850 ms");
        assertThat(DateUtils.formatDuration(999_600_000L)).isEqualTo("1 s");
        assertThat(DateUtils.formatDuration(59 * second)).isEqualTo("59 s");
        assertThat(DateUtils.formatDuration(125 * second)).isEqualTo("2 min 5 s");
        // Parcours prod du 20/10/2026 : 1 630 546 ms
        assertThat(DateUtils.formatDuration(1_630_546_000_000L)).isEqualTo("27 min 11 s");
        assertThat(DateUtils.formatDuration((3600 + 28 * 60 + 40) * second)).isEqualTo("1 h 28 min");
    }

    @Test
    void dateCouranteAuxDeuxFormats() {
        assertThat(DateUtils.getCurrentDateTime()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
        assertThat(DateUtils.getCurrentDateTimeForFileName()).matches("\\d{4}(-\\d{2}){5}");
    }
}

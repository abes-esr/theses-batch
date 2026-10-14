package fr.theses.batch.job;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RunTimestampTest {

    private String saved;

    @BeforeEach
    void save() {
        saved = System.getProperty(RunTimestamp.PROPERTY);
    }

    @AfterEach
    void restore() {
        if (saved == null) {
            System.clearProperty(RunTimestamp.PROPERTY);
        } else {
            System.setProperty(RunTimestamp.PROPERTY, saved);
        }
    }

    @Test
    void initialize_fixeLHorodatageSIlEstAbsent() {
        System.clearProperty(RunTimestamp.PROPERTY);

        RunTimestamp.initialize();

        assertThat(System.getProperty(RunTimestamp.PROPERTY)).matches("\\d{4}(-\\d{2}){5}");
    }

    @Test
    void initialize_conserveUnHorodatageImpose() {
        System.setProperty(RunTimestamp.PROPERTY, "impose");

        RunTimestamp.initialize();

        assertThat(System.getProperty(RunTimestamp.PROPERTY)).isEqualTo("impose");
    }

    @Test
    void value() {
        assertThat(new RunTimestamp("2026-01-02-03-04-05").value()).isEqualTo("2026-01-02-03-04-05");
    }
}

package fr.theses.batch.job.listener;

import fr.theses.batch.business.anr.model.dto.ANRMatchDTO;
import fr.theses.batch.job.reader.ANRReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.Chunk;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ANRProgressListenerTest {

    private static final long SECOND = 1_000_000_000L;

    private final AtomicLong clock = new AtomicLong();
    private ANRReader reader;

    @BeforeEach
    void setUp() {
        reader = mock(ANRReader.class);
        when(reader.getTotalToRead()).thenReturn(10);
    }

    private ANRProgressListener listener(Duration interval) {
        ANRProgressListener listener = new ANRProgressListener(reader, interval, clock::get);
        listener.beforeStep(mock(StepExecution.class));
        return listener;
    }

    private static ANRMatchDTO item(String errorMessage) {
        return new ANRMatchDTO("/in/a.pdf", "a.pdf", new ArrayList<>(), 0, 0, 0.0, errorMessage, null, null,
                (LocalDate) null);
    }

    @Test
    void compteCorrespondancesErreursEtFichiersFiltres() {
        ANRProgressListener listener = listener(Duration.ZERO);
        for (int i = 0; i < 4; i++) {
            listener.afterRead(item(null));
        }
        // 4 lus, 1 filtré : le chunk écrit contient une correspondance et une erreur... plus une correspondance
        listener.afterWrite(new Chunk<>(item(null), item("Erreur PDF"), item(null)));
        clock.set(2 * SECOND);

        assertThat(listener.progressMessage(clock.get())).isEqualTo(
                "Progression : 4/10 fichiers (40,0 %) — 2 avec correspondance, 1 en erreur, 1 sans correspondance"
                        + " — 2,0 fichiers/s — reste ≈ 3 s");
    }

    @Test
    void sansFichierLu_pasDeDebitNiDeTempsRestant() {
        assertThat(listener(Duration.ZERO).progressMessage(0)).isEqualTo(
                "Progression : 0/10 fichiers (0,0 %) — 0 avec correspondance, 0 en erreur, 0 sans correspondance");
    }

    @Test
    void totalNul_affiche100Pourcent() {
        when(reader.getTotalToRead()).thenReturn(0);

        assertThat(listener(Duration.ZERO).progressMessage(0)).startsWith("Progression : 0/0 fichiers (100,0 %)");
    }

    @Test
    void chunkVide_compteQuandMemeLesFichiersFiltres() {
        ANRProgressListener listener = listener(Duration.ZERO);
        listener.afterRead(item(null));
        listener.afterRead(item(null));
        listener.afterWrite(new Chunk<>());

        assertThat(listener.progressMessage(SECOND)).contains("2/10").contains("2 sans correspondance");
    }

    @Test
    void uneLigneAuPlusParIntervalle() {
        ANRProgressListener listener = listener(Duration.ofSeconds(30));

        assertThat(listener.dueForLog(10 * SECOND)).isFalse();
        assertThat(listener.dueForLog(30 * SECOND)).isTrue();
        assertThat(listener.dueForLog(50 * SECOND)).isFalse();
        assertThat(listener.dueForLog(60 * SECOND)).isTrue();
    }

    @Test
    void intervalleNul_uneLigneParChunk() {
        ANRProgressListener listener = listener(Duration.ZERO);

        assertThat(listener.dueForLog(0)).isTrue();
        assertThat(listener.dueForLog(0)).isTrue();
    }

    @Test
    void afterWrite_metAJourLesCompteursDansLesDeuxCas() {
        ANRProgressListener listener = listener(Duration.ofSeconds(30));
        listener.afterRead(item(null));
        listener.afterRead(item(null));
        clock.set(10 * SECOND);
        listener.afterWrite(new Chunk<>(item(null)));
        clock.set(40 * SECOND);
        listener.afterWrite(new Chunk<>(item("Erreur PDF")));

        assertThat(listener.progressMessage(clock.get()))
                .contains("2/10").contains("1 avec correspondance, 1 en erreur, 0 sans correspondance");
    }

    @Test
    void afterStep_rendLeStatutDuStep() {
        ANRProgressListener listener = listener(Duration.ZERO);
        StepExecution stepExecution = mock(StepExecution.class);
        when(stepExecution.getExitStatus()).thenReturn(ExitStatus.COMPLETED);

        assertThat(listener.afterStep(stepExecution)).isEqualTo(ExitStatus.COMPLETED);
    }

    @Test
    void formatDuration_alaSecondeSousLHeure_alaMinuteAuDela() {
        assertThat(ANRProgressListener.formatDuration(0)).isEqualTo("0 s");
        assertThat(ANRProgressListener.formatDuration(59 * SECOND)).isEqualTo("59 s");
        assertThat(ANRProgressListener.formatDuration(125 * SECOND)).isEqualTo("2 min 5 s");
        assertThat(ANRProgressListener.formatDuration((3600 + 28 * 60 + 40) * SECOND)).isEqualTo("1 h 28 min");
        assertThat(ANRProgressListener.formatDuration(-SECOND)).isEqualTo("0 s");
    }
}

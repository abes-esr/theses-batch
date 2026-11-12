package fr.theses.batch.job.listener;

import fr.theses.batch.business.anr.model.dto.ANRMatchDTO;
import fr.theses.batch.business.anr.model.dto.ANRPageMatchDTO;
import fr.theses.batch.job.reader.ANRReader;
import fr.theses.batch.util.parser.PDFExtraction;
import fr.theses.batch.util.parser.PDFTextExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.Chunk;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ANRProgressListenerTest {

    private static final long SECOND = 1_000_000_000L;

    private final AtomicLong clock = new AtomicLong();
    private final ANRRunStatistics statistics = new ANRRunStatistics();
    private ANRReader reader;

    @BeforeEach
    void setUp() {
        reader = mock(ANRReader.class);
        when(reader.getTotalToRead()).thenReturn(10);
    }

    private ANRProgressListener listener(Duration interval) {
        ANRProgressListener listener = new ANRProgressListener(reader, statistics, interval, clock::get);
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

    private static ANRMatchDTO itemWithMatches(LocalDate defenseDate, String... values) {
        List<ANRPageMatchDTO> matches = Arrays.stream(values)
                .map(value -> new ANRPageMatchDTO(1, value, "", ""))
                .toList();
        return item(null).withPageMatches(matches).withDefenseDate(defenseDate);
    }

    private static PDFExtraction extraction(int pages, int glyphs, int lost, int recovered, long size) {
        List<PDFTextExtractor.PDFPage> list = new ArrayList<>();
        for (int i = 1; i <= pages; i++) {
            list.add(new PDFTextExtractor.PDFPage(i, ""));
        }
        return new PDFExtraction(list, pages, glyphs, lost, recovered, size);
    }

    @Test
    void bilan_reprendLaProgressionEtLesStatistiquesDuRun() {
        ANRProgressListener listener = listener(Duration.ofHours(1));
        for (int i = 0; i < 4; i++) {
            listener.afterRead(item(null));
        }
        statistics.record("A/a.pdf", extraction(10, 1000, 10, 0, 3 * 1_048_576L), SECOND, false);
        statistics.record("B/b.pdf", extraction(20, 1000, 0, 30, 5 * 1_048_576L), 3 * SECOND, true);
        statistics.record("C/c.pdf", null, 2 * SECOND, false);
        listener.afterWrite(new Chunk<>(
                itemWithMatches(LocalDate.of(2020, 5, 1), "ANR-20-ABCD", "ANR-20-ABCD"),
                itemWithMatches(LocalDate.of(2019, 1, 1), "ANR-19-WXYZ"),
                item("Erreur PDF")));

        assertThat(listener.summaryMessage("COMPLETED", 2 * SECOND)).isEqualTo(String.join(System.lineSeparator(),
                "Fin du traitement (COMPLETED) : 4/10 fichiers en 2 s — 2,0 fichiers/s en moyenne",
                "    Résultats : 2 avec correspondance, 1 en erreur, 1 sans correspondance",
                "    Correspondances : 3 (2 valeurs distinctes) — fichiers écrits par année de soutenance :"
                        + " 2019 : 1, 2020 : 1, inconnue : 1",
                "    Lecture : 30 pages, 8,0 Mo de PDF (4,0 Mo par fichier, 4,0 Mo/s)",
                "    Texte : 0,5 % des glyphes perdus (1 fichier(s)), 1,5 % reconstitués via les polices système (1 fichier(s))",
                "    Avertissements PDFBox non bénins : 1 fichier(s)",
                "    Fichier le plus lent : B/b.pdf (3,0 s)"));
    }

    @Test
    void bilan_sansFichier_neDonneQueLesCompteurs() {
        when(reader.getTotalToRead()).thenReturn(0);

        assertThat(listener(Duration.ZERO).summaryMessage("COMPLETED", 0)).isEqualTo(String.join(System.lineSeparator(),
                "Fin du traitement (COMPLETED) : 0/0 fichiers en 0 s",
                "    Résultats : 0 avec correspondance, 0 en erreur, 0 sans correspondance",
                "    Correspondances : 0 (0 valeurs distinctes)",
                "    Avertissements PDFBox non bénins : 0 fichier(s)"));
    }

    @Test
    void bilan_sansAnneeInconnue_pasDeVirguleFinale() {
        ANRProgressListener listener = listener(Duration.ZERO);
        listener.afterRead(item(null));
        listener.afterWrite(new Chunk<>(itemWithMatches(LocalDate.of(2021, 1, 1), "ANR-21-AAAA")));

        assertThat(listener.summaryMessage("COMPLETED", SECOND))
                .contains("par année de soutenance : 2021 : 1" + System.lineSeparator());
    }

    @Test
    void beforeStep_remetLesStatistiquesAZero() {
        statistics.record("a.pdf", extraction(1, 1, 0, 0, 1), SECOND, true);

        listener(Duration.ZERO);

        assertThat(statistics.extractedFiles()).isZero();
        assertThat(statistics.filesWithHarmfulWarnings()).isZero();
        assertThat(statistics.slowestFile()).isNull();
    }

    @Test
    void formatSize_passeEnGoAPartirDe1Go() {
        assertThat(ANRProgressListener.formatSize(512 * 1_048_576L)).isEqualTo("512,0 Mo");
        assertThat(ANRProgressListener.formatSize(1536 * 1_048_576L)).isEqualTo("1,5 Go");
    }

    @Test
    void afterStep_rendLeStatutDuStep() {
        ANRProgressListener listener = listener(Duration.ZERO);
        StepExecution stepExecution = mock(StepExecution.class);
        when(stepExecution.getExitStatus()).thenReturn(ExitStatus.COMPLETED);

        assertThat(listener.afterStep(stepExecution)).isEqualTo(ExitStatus.COMPLETED);
    }

    /**
     * Lit n fichiers puis écrit un chunk à l'instant donné
     */
    private void chunk(ANRProgressListener listener, int n, long at) {
        for (int i = 0; i < n; i++) {
            listener.afterRead(item(null));
        }
        clock.set(at);
        listener.afterWrite(new Chunk<>());
    }

    @Test
    void debitSurLaFenetreGlissante_etMoyenneDepuisLeDebut() {
        when(reader.getTotalToRead()).thenReturn(10_000);
        ANRProgressListener listener = listener(Duration.ZERO);
        long window = ANRProgressListener.RATE_WINDOW.toSeconds();
        // Départ rapide : 2000 fichiers la première minute, puis 1 fichier/s
        chunk(listener, 2000, 60 * SECOND);
        for (long t = 70; t <= 60 + window; t += 10) {
            chunk(listener, 10, t * SECOND);
        }

        // Fenêtre [60 s, 360 s] : 300 fichiers en 300 s ; moyenne : 2300 en 360 s
        assertThat(listener.progressMessage(clock.get())).endsWith(
                "— 1,0 fichiers/s sur 5 min 0 s (moy. 6,4) — reste ≈ 2 h 8 min");
    }

    @Test
    void stepPlusCourtQueLaFenetre_pasDeMoyenneSeparee() {
        ANRProgressListener listener = listener(Duration.ZERO);
        chunk(listener, 3, SECOND);
        chunk(listener, 3, 2 * SECOND);

        assertThat(listener.progressMessage(clock.get())).endsWith("— 3,0 fichiers/s — reste ≈ 1 s");
    }

    @Test
    void aucunChunkDansLaFenetre_debitDepuisLeDernierReleve() {
        when(reader.getTotalToRead()).thenReturn(1000);
        ANRProgressListener listener = listener(Duration.ZERO);
        chunk(listener, 50, 10 * SECOND);
        // Dix minutes sans chunk terminé (fichier très lent), puis 100 fichiers
        chunk(listener, 100, 610 * SECOND);

        // Référence : relevé de 10 s, le seul antérieur à la fenêtre → 100 fichiers en 600 s
        assertThat(listener.progressMessage(clock.get())).endsWith(
                "— 0,2 fichiers/s sur 10 min 0 s (moy. 0,2) — reste ≈ 1 h 25 min");
    }
}

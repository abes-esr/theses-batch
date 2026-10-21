package fr.theses.batch.job.listener;

import fr.theses.batch.business.anr.model.dto.ANRMatchDTO;
import fr.theses.batch.job.reader.ANRReader;
import fr.theses.batch.util.date.DateUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.listener.ItemReadListener;
import org.springframework.batch.core.listener.ItemWriteListener;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Locale;
import java.util.function.LongSupplier;

/**
 * Journalise l'avancement du step ANR : fichiers traités sur le total, correspondances, erreurs,
 * débit sur les dernières minutes et temps restant estimé à ce débit.
 * <p>
 * Une ligne au plus par intervalle app.anr.progress-interval (0 = à chaque chunk), émise après
 * l'écriture d'un chunk, et un bilan en fin de step.
 * <p>
 * Pas de ChunkListener : Spring Batch 6 ne l'appelle pas quand les items d'un chunk sont traités en
 * parallèle (process-threads &gt; 1). L'écriture, elle, est notifiée dans les deux modes, et
 * afterRead comme afterWrite s'exécutent sur le thread du step : aucun compteur n'est partagé avec
 * les threads de traitement. Les compteurs du StepExecution ne servent pas non plus : ils ne sont
 * mis à jour qu'après afterWrite, avec un chunk de retard.
 */
@Component
@Slf4j
public class ANRProgressListener
        implements StepExecutionListener, ItemReadListener<ANRMatchDTO>, ItemWriteListener<ANRMatchDTO> {

    /**
     * Fenêtre sur laquelle sont calculés le débit affiché et le temps restant
     */
    static final Duration RATE_WINDOW = Duration.ofMinutes(5);
    private static final long RATE_WINDOW_NANOS = RATE_WINDOW.toNanos();

    private final ANRReader reader;
    private final long intervalNanos;
    private final LongSupplier nanoClock;
    /**
     * Relevés {instant, fichiers lus} pris à chaque chunk, du plus ancien au plus récent ; ne garde
     * que ceux de la fenêtre, plus celui qui la précède immédiatement
     */
    private final Deque<long[]> samples = new ArrayDeque<>();

    private long startNanos;
    private long lastLogNanos;
    private long read;
    private long written;
    private long errors;

    @Autowired
    public ANRProgressListener(ANRReader reader,
                               @Value("${app.anr.progress-interval:30s}") Duration interval) {
        this(reader, interval, System::nanoTime);
    }

    ANRProgressListener(ANRReader reader, Duration interval, LongSupplier nanoClock) {
        this.reader = reader;
        this.intervalNanos = interval.toNanos();
        this.nanoClock = nanoClock;
    }

    @Override
    public void beforeStep(StepExecution stepExecution) {
        startNanos = nanoClock.getAsLong();
        lastLogNanos = startNanos;
        read = 0;
        written = 0;
        errors = 0;
        samples.clear();
        samples.add(new long[]{startNanos, 0});
        log.info("Début du traitement : {} fichiers à traiter", reader.getTotalToRead());
    }

    @Override
    public void afterRead(ANRMatchDTO item) {
        read++;
    }

    /**
     * Appelé après chaque chunk, même quand tous ses fichiers ont été filtrés (chunk vide) : à ce
     * moment, chaque fichier lu a été traité.
     */
    @Override
    public void afterWrite(Chunk<? extends ANRMatchDTO> chunk) {
        written += chunk.size();
        for (ANRMatchDTO item : chunk) {
            if (item.errorMessage() != null) {
                errors++;
            }
        }
        long now = nanoClock.getAsLong();
        samples.add(new long[]{now, read});
        if (dueForLog(now)) {
            log.info("{}", progressMessage(now));
        }
    }

    /**
     * Vrai si l'intervalle est écoulé depuis la dernière ligne de progression (ou le début du step),
     * qui est alors datée de now.
     */
    boolean dueForLog(long now) {
        if (now - lastLogNanos < intervalNanos) {
            return false;
        }
        lastLogNanos = now;
        return true;
    }

    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        long elapsed = nanoClock.getAsLong() - startNanos;
        log.info("Fin du traitement ({}) : {} fichiers en {} — {}",
                stepExecution.getExitStatus().getExitCode(), read, DateUtils.formatDuration(elapsed), counts());
        return stepExecution.getExitStatus();
    }

    /**
     * Ex. : « Progression : 12400/229812 fichiers (5,4 %) — 37 avec correspondance, 3 en erreur,
     * 12360 sans correspondance — 5,8 fichiers/s sur 5 min (moy. 6,4) — reste ≈ 1 h 28 min »
     * <p>
     * Le débit et le temps restant portent sur la fenêtre glissante RATE_WINDOW : la moyenne depuis le
     * début du step garde la trace d'un départ rapide ou lent bien après qu'il est passé. La moyenne
     * n'est ajoutée qu'une fois le step plus long que la fenêtre, quand les deux diffèrent.
     */
    String progressMessage(long now) {
        long total = reader.getTotalToRead();
        StringBuilder message = new StringBuilder(String.format(Locale.FRANCE,
                "Progression : %d/%d fichiers (%.1f %%) — %s", read, total,
                total == 0 ? 100.0 : 100.0 * read / total, counts()));
        long[] reference = windowStart(now);
        long windowNanos = now - reference[0];
        long windowRead = read - reference[1];
        if (windowNanos > 0 && windowRead > 0) {
            double perSecond = windowRead * 1e9 / windowNanos;
            message.append(String.format(Locale.FRANCE, " — %.1f fichiers/s", perSecond));
            if (reference[0] != startNanos) {
                message.append(String.format(Locale.FRANCE, " sur %s (moy. %.1f)",
                        DateUtils.formatDuration(windowNanos), read * 1e9 / (now - startNanos)));
            }
            long remainingNanos = (long) ((total - read) / perSecond * 1e9);
            message.append(" — reste ≈ ").append(DateUtils.formatDuration(remainingNanos));
        }
        return message.toString();
    }

    /**
     * Point de départ de la fenêtre glissante : le relevé le plus récent qui date d'au moins
     * RATE_WINDOW avant now, ou le début du step si aucun n'est aussi ancien. Les relevés plus
     * anciens ne serviront plus et sont oubliés.
     *
     * @return {instant en nanosecondes, nombre de fichiers lus à cet instant}
     */
    private long[] windowStart(long now) {
        long limit = now - RATE_WINDOW_NANOS;
        while (samples.size() > 1) {
            Iterator<long[]> it = samples.iterator();
            it.next();
            if (it.next()[0] > limit) {
                break;
            }
            samples.removeFirst();
        }
        return samples.getFirst();
    }

    private String counts() {
        return String.format("%d avec correspondance, %d en erreur, %d sans correspondance",
                written - errors, errors, read - written);
    }
}

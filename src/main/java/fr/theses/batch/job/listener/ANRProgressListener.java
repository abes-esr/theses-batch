package fr.theses.batch.job.listener;

import fr.theses.batch.business.anr.model.dto.ANRMatchDTO;
import fr.theses.batch.job.reader.ANRReader;
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
import java.util.Locale;
import java.util.function.LongSupplier;

/**
 * Journalise l'avancement du step ANR : fichiers traités sur le total, correspondances, erreurs,
 * débit et temps restant estimé.
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

    private final ANRReader reader;
    private final long intervalNanos;
    private final LongSupplier nanoClock;

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
                stepExecution.getExitStatus().getExitCode(), read, formatDuration(elapsed), counts());
        return stepExecution.getExitStatus();
    }

    /**
     * Ex. : « Progression : 12400/229812 fichiers (5,4 %) — 37 avec correspondance, 3 en erreur,
     * 12360 sans correspondance — 41,2 fichiers/s — reste ≈ 1 h 28 min »
     */
    String progressMessage(long now) {
        long total = reader.getTotalToRead();
        long elapsed = now - startNanos;
        StringBuilder message = new StringBuilder(String.format(Locale.FRANCE,
                "Progression : %d/%d fichiers (%.1f %%) — %s", read, total,
                total == 0 ? 100.0 : 100.0 * read / total, counts()));
        if (elapsed > 0 && read > 0) {
            double perSecond = read * 1e9 / elapsed;
            long remainingNanos = (long) ((total - read) / perSecond * 1e9);
            message.append(String.format(Locale.FRANCE, " — %.1f fichiers/s — reste ≈ %s",
                    perSecond, formatDuration(remainingNanos)));
        }
        return message.toString();
    }

    private String counts() {
        return String.format("%d avec correspondance, %d en erreur, %d sans correspondance",
                written - errors, errors, read - written);
    }

    /**
     * Durée lisible à la minute près au-delà d'une heure, à la seconde près en deçà.
     */
    static String formatDuration(long nanos) {
        long seconds = Math.max(0, Math.round(nanos / 1e9));
        long hours = seconds / 3600;
        long minutes = seconds % 3600 / 60;
        if (hours > 0) {
            return hours + " h " + minutes + " min";
        }
        if (minutes > 0) {
            return minutes + " min " + seconds % 60 + " s";
        }
        return seconds + " s";
    }
}

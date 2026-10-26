package fr.theses.batch.job.listener;

import fr.theses.batch.business.anr.model.dto.ANRMatchDTO;
import fr.theses.batch.business.anr.model.dto.ANRPageMatchDTO;
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

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryType;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.LongSupplier;

/**
 * Journalise l'avancement du step ANR : fichiers traités sur le total, correspondances, erreurs,
 * débit sur les dernières minutes et temps restant estimé à ce débit.
 * <p>
 * Une ligne au plus par intervalle app.anr.progress-interval (0 = à chaque chunk), émise après
 * l'écriture d'un chunk, et un bilan détaillé en fin de step (voir summaryMessage).
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
    private static final long MIB = 1_048_576L;

    private final ANRReader reader;
    private final long intervalNanos;
    private final LongSupplier nanoClock;
    /**
     * Relevés {instant, fichiers lus} pris à chaque chunk, du plus ancien au plus récent ; ne garde
     * que ceux de la fenêtre, plus celui qui la précède immédiatement
     */
    private final Deque<long[]> samples = new ArrayDeque<>();

    private final ANRRunStatistics statistics;
    /**
     * Valeurs ANR distinctes trouvées, pour le bilan
     */
    private final Set<String> distinctMatchValues = new HashSet<>();
    /**
     * Fichiers écrits (correspondance ou erreur) par année de soutenance, pour le bilan
     */
    private final Map<Integer, Long> filesByYear = new TreeMap<>();

    private long startNanos;
    private long lastLogNanos;
    private long read;
    private long written;
    private long errors;
    private long matches;
    private long filesWithoutYear;

    @Autowired
    public ANRProgressListener(ANRReader reader, ANRRunStatistics statistics,
                               @Value("${app.anr.progress-interval:30s}") Duration interval) {
        this(reader, statistics, interval, System::nanoTime);
    }

    ANRProgressListener(ANRReader reader, ANRRunStatistics statistics, Duration interval, LongSupplier nanoClock) {
        this.reader = reader;
        this.statistics = statistics;
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
        matches = 0;
        distinctMatchValues.clear();
        filesByYear.clear();
        filesWithoutYear = 0;
        statistics.reset();
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
            matches += item.pageMatches().size();
            for (ANRPageMatchDTO match : item.pageMatches()) {
                distinctMatchValues.add(match.matchValue());
            }
            if (item.defenseDate() != null) {
                filesByYear.merge(item.defenseDate().getYear(), 1L, Long::sum);
            } else {
                filesWithoutYear++;
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
        log.info("{}{}", summaryMessage(stepExecution.getExitStatus().getExitCode(), nanoClock.getAsLong()),
                jvmLine());
        return stepExecution.getExitStatus();
    }

    /**
     * Bilan de fin de step, ex. :
     * <pre>
     * Fin du traitement (COMPLETED) : 229812/229812 fichiers en 7 h 31 min — 8,5 fichiers/s en moyenne
     *     Résultats : 1234 avec correspondance, 12 en erreur, 228566 sans correspondance
     *     Correspondances : 5678 (3456 valeurs distinctes) — fichiers écrits par année de soutenance : 2019 : 600, inconnue : 646
     *     Lecture : 1234567 pages, 4321,0 Go de PDF (19,2 Mo par fichier, 163,5 Mo/s)
     *     Texte : 0,8 % des glyphes perdus (345 fichier(s)), 1,2 % reconstitués via les polices système (678 fichier(s))
     *     Avertissements PDFBox non bénins : 456 fichier(s)
     *     Fichier le plus lent : ETAB/THESE_1/document/these.pdf (123,4 s)
     * </pre>
     * Hors la première ligne et les résultats, les compteurs proviennent d'ANRRunStatistics,
     * alimenté par le processor pour chaque fichier, avec ou sans correspondance.
     */
    String summaryMessage(String exitCode, long now) {
        long elapsed = now - startNanos;
        double seconds = elapsed / 1e9;
        StringBuilder message = new StringBuilder(String.format(Locale.FRANCE,
                "Fin du traitement (%s) : %d/%d fichiers en %s", exitCode, read, reader.getTotalToRead(),
                DateUtils.formatDuration(elapsed)));
        if (elapsed > 0 && read > 0) {
            message.append(String.format(Locale.FRANCE, " — %.1f fichiers/s en moyenne", read / seconds));
        }
        line(message, "Résultats : " + counts());

        StringBuilder results = new StringBuilder(String.format("Correspondances : %d (%d valeurs distinctes)",
                matches, distinctMatchValues.size()));
        if (!filesByYear.isEmpty() || filesWithoutYear > 0) {
            results.append(" — fichiers écrits par année de soutenance : ");
            filesByYear.forEach((year, count) -> results.append(year).append(" : ").append(count).append(", "));
            if (filesWithoutYear > 0) {
                results.append("inconnue : ").append(filesWithoutYear);
            } else {
                results.setLength(results.length() - 2);
            }
        }
        line(message, results);

        long files = statistics.extractedFiles();
        if (files > 0) {
            StringBuilder reading = new StringBuilder(String.format(Locale.FRANCE, "Lecture : %d pages, %s de PDF (%s par fichier",
                    statistics.pages(), formatSize(statistics.bytes()), formatSize(statistics.bytes() / files)));
            if (elapsed > 0) {
                reading.append(", ").append(formatSize((long) (statistics.bytes() / seconds))).append("/s");
            }
            line(message, reading.append(')'));
            line(message, String.format(Locale.FRANCE,
                    "Texte : %.1f %% des glyphes perdus (%d fichier(s)), %.1f %% reconstitués via les polices système (%d fichier(s))",
                    percent(statistics.lostGlyphs(), statistics.glyphs()), statistics.filesWithLostText(),
                    percent(statistics.recoveredGlyphs(), statistics.glyphs()), statistics.filesWithRecoveredText()));
        }
        line(message, "Avertissements PDFBox non bénins : " + statistics.filesWithHarmfulWarnings() + " fichier(s)");
        if (statistics.slowestFile() != null) {
            line(message, String.format(Locale.FRANCE, "Fichier le plus lent : %s (%.1f s)",
                    statistics.slowestFile(), statistics.slowestNanos() / 1e9));
        }
        return message.toString();
    }

    /**
     * Mémoire et GC depuis le démarrage de la JVM, ex. « JVM : heap max 6144 Mo, pic ≤ 5900 Mo — GC : 1234
     * collections, 45 s (0,2 % du temps) ». Le pic est la somme des pics de chaque zone du heap, atteints
     * à des instants différents et déchets compris : un majorant, pas le volume des objets vivants.
     */
    private static String jvmLine() {
        long peak = ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(pool -> pool.getType() == MemoryType.HEAP)
                .mapToLong(pool -> pool.getPeakUsage().getUsed())
                .sum();
        long collections = 0;
        long gcMillis = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            collections += Math.max(0, gc.getCollectionCount());
            gcMillis += Math.max(0, gc.getCollectionTime());
        }
        long uptime = ManagementFactory.getRuntimeMXBean().getUptime();
        return String.format(Locale.FRANCE, "%n    JVM : heap max %d Mo, pic ≤ %d Mo — GC : %d collections, %s (%.1f %% du temps)",
                Runtime.getRuntime().maxMemory() / MIB, peak / MIB, collections,
                DateUtils.formatDuration(gcMillis * 1_000_000L), percent(gcMillis, uptime));
    }

    private static void line(StringBuilder message, CharSequence line) {
        message.append(String.format("%n    ")).append(line);
    }

    /**
     * Taille en Mo, ou en Go à partir de 1 Go (unités binaires, comme dans les lignes par fichier)
     */
    static String formatSize(long bytes) {
        return bytes >= 1024 * MIB
                ? String.format(Locale.FRANCE, "%.1f Go", bytes / (1024.0 * MIB))
                : String.format(Locale.FRANCE, "%.1f Mo", bytes / (double) MIB);
    }

    private static double percent(long part, long whole) {
        return whole == 0 ? 0 : 100.0 * part / whole;
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

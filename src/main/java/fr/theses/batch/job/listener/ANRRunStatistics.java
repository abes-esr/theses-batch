package fr.theses.batch.job.listener;

import fr.theses.batch.util.parser.PDFExtraction;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.LongAdder;

/**
 * Statistiques d'extraction cumulées sur tout le run, pour le bilan de fin de step
 * ({@link ANRProgressListener}).
 * <p>
 * Alimentées par ANRProcessor pour chaque fichier, y compris ceux sans correspondance, qui ne
 * parviennent jamais au listener : appelées depuis les threads de traitement, d'où les LongAdder.
 */
@Component
public class ANRRunStatistics {

    private final LongAdder extractedFiles = new LongAdder();
    private final LongAdder pages = new LongAdder();
    private final LongAdder bytes = new LongAdder();
    private final LongAdder glyphs = new LongAdder();
    private final LongAdder lostGlyphs = new LongAdder();
    private final LongAdder recoveredGlyphs = new LongAdder();
    private final LongAdder filesWithLostText = new LongAdder();
    private final LongAdder filesWithRecoveredText = new LongAdder();
    private final LongAdder filesWithHarmfulWarnings = new LongAdder();

    private String slowestFile;
    private long slowestNanos = -1;

    /**
     * Remet les compteurs à zéro, en début de step
     */
    public synchronized void reset() {
        extractedFiles.reset();
        pages.reset();
        bytes.reset();
        glyphs.reset();
        lostGlyphs.reset();
        recoveredGlyphs.reset();
        filesWithLostText.reset();
        filesWithRecoveredText.reset();
        filesWithHarmfulWarnings.reset();
        slowestFile = null;
        slowestNanos = -1;
    }

    /**
     * Enregistre un fichier traité.
     *
     * @param relativePath    Chemin du fichier relatif à app.anr.input-dir
     * @param extraction      Résultat de l'extraction, null si le fichier n'a pas pu être lu
     * @param elapsedNanos    Durée de traitement du fichier
     * @param harmfulWarnings Vrai si PDFBox a émis au moins un avertissement non bénin
     */
    public void record(String relativePath, PDFExtraction extraction, long elapsedNanos, boolean harmfulWarnings) {
        if (extraction != null) {
            extractedFiles.increment();
            pages.add(extraction.pages().size());
            bytes.add(extraction.fileSize());
            glyphs.add(extraction.glyphs());
            lostGlyphs.add(extraction.lostGlyphs());
            recoveredGlyphs.add(extraction.recoveredGlyphs());
            if (extraction.lostGlyphs() > 0) {
                filesWithLostText.increment();
            }
            if (extraction.recoveredGlyphs() > 0) {
                filesWithRecoveredText.increment();
            }
        }
        if (harmfulWarnings) {
            filesWithHarmfulWarnings.increment();
        }
        synchronized (this) {
            if (elapsedNanos > slowestNanos) {
                slowestNanos = elapsedNanos;
                slowestFile = relativePath;
            }
        }
    }

    /**
     * Fichiers dont le texte a pu être extrait (hors erreurs de lecture)
     */
    public long extractedFiles() {
        return extractedFiles.sum();
    }

    public long pages() {
        return pages.sum();
    }

    /**
     * Taille cumulée des fichiers lus, pas le volume réellement transféré (lecture par blocs)
     */
    public long bytes() {
        return bytes.sum();
    }

    public long glyphs() {
        return glyphs.sum();
    }

    public long lostGlyphs() {
        return lostGlyphs.sum();
    }

    public long recoveredGlyphs() {
        return recoveredGlyphs.sum();
    }

    public long filesWithLostText() {
        return filesWithLostText.sum();
    }

    public long filesWithRecoveredText() {
        return filesWithRecoveredText.sum();
    }

    public long filesWithHarmfulWarnings() {
        return filesWithHarmfulWarnings.sum();
    }

    /**
     * Fichier le plus long à traiter, null si aucun fichier n'a été traité
     */
    public synchronized String slowestFile() {
        return slowestFile;
    }

    public synchronized long slowestNanos() {
        return slowestNanos;
    }
}

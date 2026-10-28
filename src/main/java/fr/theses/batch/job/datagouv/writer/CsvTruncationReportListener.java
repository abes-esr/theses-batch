package fr.theses.batch.job.datagouv.writer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.step.StepExecution;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * En fin d'étape d'export, journalise et écrit dans {timestamp}_datagouv_troncatures.txt les valeurs
 * qui n'ont pas trouvé place dans les colonnes fixes du CSV (elles restent complètes dans le JSON et le JSONL).
 */
@Slf4j
public class CsvTruncationReportListener implements StepExecutionListener {

    private final ThesisCsvLineAggregator csvLineAggregator;
    private final Path reportFile;

    /**
     * @param csvLineAggregator Agrégateur CSV de l'étape, qui tient le décompte des troncatures.
     * @param reportFile        Fichier de rapport à écrire.
     */
    public CsvTruncationReportListener(ThesisCsvLineAggregator csvLineAggregator, Path reportFile) {
        this.csvLineAggregator = csvLineAggregator;
        this.reportFile = reportFile;
    }

    @Override
    public ExitStatus afterStep(@NonNull StepExecution stepExecution) {
        CsvTruncationReport report = csvLineAggregator.getTruncationReport();
        List<String> lines = new ArrayList<>();
        lines.add("Valeurs absentes du CSV (colonnes en nombre fixe), complètes dans le JSON et le JSONL.");
        lines.add("Thèses écrites : " + stepExecution.getWriteCount());
        if (report.hasTruncations()) {
            lines.addAll(report.lines());
            log.warn("CSV : valeurs au-delà des colonnes du format historique (complètes dans le JSON/JSONL) :\n  {}",
                    String.join("\n  ", report.lines()));
        } else {
            lines.add("Aucune valeur tronquée.");
            log.info("CSV : aucune valeur tronquée.");
        }

        try {
            Files.write(reportFile, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Impossible d'écrire le rapport de troncature " + reportFile, e);
        }
        log.info("Rapport de troncature du CSV écrit : {}", reportFile.toAbsolutePath());
        return stepExecution.getExitStatus();
    }
}

package fr.theses.batch.job.tasklet;

import fr.theses.batch.job.writer.csv.DatagouvJsonFlattener;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.QuoteMode;
import org.jspecify.annotations.NonNull;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Tasklet Spring Batch générant le CSV en aplatissant le JSONL déjà écrit (voir {@link DatagouvJsonFlattener}).
 * <p>
 * Deux lectures en flux du JSONL : la première recense tous les chemins rencontrés, qui forment
 * les colonnes, la seconde écrit une ligne par thèse. Aucune valeur n'est perdue et la mémoire
 * utilisée ne dépend que du nombre de colonnes, pas du nombre de thèses.
 * <p>
 * Les colonnes sont triées par ordre lexicographique (membres_jury.0, membres_jury.10, membres_jury.1...),
 * comme dans le format historique du jeu de données.
 */
@Slf4j
public class DatagouvCsvTasklet implements Tasklet {

    // En-tête entièrement entre guillemets comme le format historique, lignes avec guillemets si nécessaire
    private static final CSVFormat HEADER_FORMAT = CSVFormat.DEFAULT.builder()
            .setQuoteMode(QuoteMode.ALL)
            .setRecordSeparator("\n")
            .build();
    private static final CSVFormat ROW_FORMAT = CSVFormat.DEFAULT.builder()
            .setRecordSeparator("\n")
            .build();

    private final File source;
    private final File target;
    private final ObjectMapper objectMapper;

    /**
     * @param outputDir    Répertoire local contenant les fichiers générés.
     * @param runTimestamp Horodatage du run au format yyyy-MM-dd-HH-mm-ss.
     * @param objectMapper Mapper JSON lisant le JSONL.
     */
    public DatagouvCsvTasklet(String outputDir, String runTimestamp, ObjectMapper objectMapper) {
        this.source = new File(outputDir, runTimestamp + "_datagouv_results.jsonl");
        this.target = new File(outputDir, runTimestamp + "_datagouv_results.csv");
        this.objectMapper = objectMapper;
    }

    @Override
    public RepeatStatus execute(@NonNull StepContribution contribution, @NonNull ChunkContext chunkContext) throws IOException {
        if (!source.exists()) {
            throw new IllegalStateException("Le fichier " + source.getAbsolutePath() + " à convertir en CSV n'existe pas.");
        }

        Set<String> columns = new TreeSet<>();
        forEachThesis(values -> columns.addAll(values.keySet()));
        List<String> header = new ArrayList<>(columns);

        long rows;
        try (Writer out = Files.newBufferedWriter(target.toPath(), StandardCharsets.UTF_8)) {
            // BOM UTF-8 : Excel reconnaît ainsi l'encodage du fichier
            out.write('﻿');
            out.write(HEADER_FORMAT.format(header.toArray()));
            out.write('\n');
            CSVPrinter printer = new CSVPrinter(out, ROW_FORMAT);
            long[] count = {0};
            forEachThesis(values -> {
                List<String> row = new ArrayList<>(header.size());
                for (String column : header) {
                    row.add(values.getOrDefault(column, ""));
                }
                try {
                    printer.printRecord(row);
                } catch (IOException e) {
                    throw new IllegalStateException("Erreur d'écriture du CSV " + target.getAbsolutePath(), e);
                }
                count[0]++;
            });
            printer.flush();
            rows = count[0];
        }

        log.info("CSV {} généré depuis {} : {} thèses, {} colonnes.", target.getName(), source.getName(), rows, header.size());
        return RepeatStatus.FINISHED;
    }

    /**
     * Lit le JSONL ligne à ligne et transmet chaque thèse aplatie.
     */
    private void forEachThesis(Consumer<Map<String, String>> consumer) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(source.toPath(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    consumer.accept(DatagouvJsonFlattener.flatten(objectMapper.readTree(line)));
                }
            }
        }
    }
}

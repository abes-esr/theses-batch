package fr.theses.batch.job.writer.csv;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Comptabilise les valeurs qui ne tiennent pas dans les colonnes fixes du CSV historique
 * (elles restent complètes dans le JSON et le JSONL).
 */
public class DatagouvCsvTruncationReport {

    private final Map<String, FieldStats> stats = new TreeMap<>();

    /**
     * Enregistre la taille d'une liste pour un champ du CSV limité à limit valeurs.
     *
     * @param field Nom du champ dans l'export.
     * @param size  Nombre de valeurs de la thèse pour ce champ.
     * @param limit Nombre de valeurs que le CSV peut contenir.
     */
    public synchronized void record(String field, int size, int limit) {
        if (size > limit) {
            stats.computeIfAbsent(field, f -> new FieldStats(limit)).add(size);
        }
    }

    /**
     * @return true si au moins une thèse a perdu des valeurs dans le CSV.
     */
    public synchronized boolean hasTruncations() {
        return !stats.isEmpty();
    }

    /**
     * @return Une ligne par champ tronqué : nombre de thèses concernées, limite et maximum rencontré.
     */
    public synchronized List<String> lines() {
        List<String> lines = new ArrayList<>();
        stats.forEach((field, s) -> lines.add(field + " : " + s.theses + " thèse(s) au-delà de " + s.limit
                + " valeur(s), maximum rencontré " + s.max));
        return lines;
    }

    private static final class FieldStats {
        private final int limit;
        private long theses;
        private int max;

        private FieldStats(int limit) {
            this.limit = limit;
        }

        private void add(int size) {
            theses++;
            max = Math.max(max, size);
        }
    }
}

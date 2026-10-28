package fr.theses.batch.job.datagouv.writer;

import fr.theses.batch.business.datagouv.model.ExportOrganization;
import fr.theses.batch.business.datagouv.model.ExportPerson;
import fr.theses.batch.business.datagouv.model.ExportThesis;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.batch.infrastructure.item.file.transform.LineAggregator;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Agrégateur de ligne CSV générant les 213 colonnes plates dans l'ordre exact attendu par data.gouv.fr.
 * <p>
 * Le nombre de colonnes du format historique est fixe : les valeurs au-delà (membres du jury,
 * sujets RAMEAU, auteurs supplémentaires, cotutelles...) ne figurent que dans le JSON et le JSONL,
 * et sont comptabilisées dans un {@link CsvTruncationReport}.
 */
public class ThesisCsvLineAggregator implements LineAggregator<ExportThesis> {

    // Délimiteur virgule standard
    private static final CSVFormat CSV_FORMAT = CSVFormat.DEFAULT.builder()
            .setDelimiter(',')
            .build();

    private static final int DIRECTEURS = 7;
    private static final int ECOLES_DOCTORALES = 2;
    private static final int ETABLISSEMENTS = 5;
    private static final int LANGUES = 4;
    private static final int PARTENAIRES = 8;
    private static final int RAPPORTEURS = 6;
    private static final int RESUMES_AUTRE = 6;
    private static final int SUJETS_AUTRE = 8;
    private static final int TITRES_AUTRE = 4;
    // Ordre lexicographique des colonnes : 0, 10, 11, 1, 2... (12 membres)
    private static final int[] MEMBRES_INDICES = {0, 10, 11, 1, 2, 3, 4, 5, 6, 7, 8, 9};
    // Ordre lexicographique des colonnes : 0, 1, 10..19, 2, 20..29... (55 sujets)
    private static final int[] RAMEAU_INDICES = {
            0, 1, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19,
            2, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29,
            3, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39,
            4, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49,
            5, 50, 51, 52, 53, 54,
            6, 7, 8, 9
    };

    private final CsvTruncationReport truncationReport = new CsvTruncationReport();

    /**
     * @return Le décompte des valeurs qui n'ont pas trouvé place dans le CSV depuis le début de l'export.
     */
    public CsvTruncationReport getTruncationReport() {
        return truncationReport;
    }

    /**
     * Agrège un objet ExportThesis en une ligne CSV formatée avec quotes automatiques.
     *
     * @param t DTO d'export de thèse.
     * @return Ligne CSV sérialisée.
     */
    @Override
    @NonNull
    public String aggregate(@NonNull ExportThesis t) {
        recordTruncations(t);
        List<String> row = new ArrayList<>();

        // 1. accessible
        row.add(t.accessible() != null ? t.accessible() : "");

        // 2. auteur
        if (t.auteur() != null) {
            row.add(t.auteur().idref() != null ? t.auteur().idref() : "");
            row.add(t.auteur().nom() != null ? t.auteur().nom() : "");
            row.add(t.auteur().prenom() != null ? t.auteur().prenom() : "");
        } else {
            row.add(""); row.add(""); row.add("");
        }

        // 3. cas
        row.add(t.cas() != null ? t.cas() : "");

        // 4. code_etab
        row.add(t.code_etab() != null ? t.code_etab() : "");

        // 5. date_soutenance
        row.add(t.date_soutenance() != null ? t.date_soutenance() : "");

        // 6. directeurs_these (0 à 6)
        for (int i = 0; i < DIRECTEURS; i++) {
            addPerson(row, t.directeurs_these(), i);
        }

        // 7. discipline
        row.add(t.discipline() != null ? t.discipline() : "");

        // 8. ecoles_doctorales (0 à 1)
        for (int i = 0; i < ECOLES_DOCTORALES; i++) {
            addOrg(row, t.ecoles_doctorales(), i, false);
        }

        // 9. embargo
        row.add(t.embargo() != null ? t.embargo() : "");

        // 10. etablissements_soutenance (0 à 4)
        for (int i = 0; i < ETABLISSEMENTS; i++) {
            addOrg(row, t.etablissements_soutenance(), i, false);
        }

        // 11. langues (0 à 3)
        for (int i = 0; i < LANGUES; i++) {
            addString(row, t.langues(), i);
        }

        // 12. membres_jury (ordre lexicographique : 0, 10, 11, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        for (int idx : MEMBRES_INDICES) {
            addPerson(row, t.membres_jury(), idx);
        }

        // 13. nnt
        row.add(t.nnt() != null ? t.nnt() : "");

        // 14. oai_set_specs
        row.add(t.oai_set_specs() != null && !t.oai_set_specs().isEmpty() ? t.oai_set_specs().get(0) : "");

        // 15. partenaires_recherche (0 à 7, avec champ type)
        for (int i = 0; i < PARTENAIRES; i++) {
            addOrg(row, t.partenaires_recherche(), i, true);
        }

        // 16. president_jury
        if (t.president_jury() != null) {
            row.add(t.president_jury().idref() != null ? t.president_jury().idref() : "");
            row.add(t.president_jury().nom() != null ? t.president_jury().nom() : "");
            row.add(t.president_jury().prenom() != null ? t.president_jury().prenom() : "");
        } else {
            row.add(""); row.add(""); row.add("");
        }

        // 17. rapporteurs (0 à 5)
        for (int i = 0; i < RAPPORTEURS; i++) {
            addPerson(row, t.rapporteurs(), i);
        }

        // 18. resumes
        List<String> resumesAutre = t.resumes() != null ? t.resumes().autre() : null;
        for (int i = 0; i < RESUMES_AUTRE; i++) {
            addString(row, resumesAutre, i);
        }
        row.add(t.resumes() != null && t.resumes().en() != null ? t.resumes().en() : "");
        row.add(t.resumes() != null && t.resumes().fr() != null ? t.resumes().fr() : "");

        // 19. source
        row.add(t.source() != null ? t.source() : "");

        // 20. status
        row.add(t.status() != null ? t.status() : "");

        // 21. sujets
        List<String> sujetsAutre = t.sujets() != null ? t.sujets().autre() : null;
        for (int i = 0; i < SUJETS_AUTRE; i++) {
            addString(row, sujetsAutre, i);
        }
        row.add(t.sujets() != null && t.sujets().en() != null ? String.join("||", t.sujets().en()) : "");
        row.add(t.sujets() != null && t.sujets().fr() != null ? String.join("||", t.sujets().fr()) : "");

        // 22. sujets_rameau (ordre lexicographique : 0, 1, 10..19, 2, 20..29, 3, 30..39, 4, 40..49, 5, 50..54, 6, 7, 8, 9)
        for (int idx : RAMEAU_INDICES) {
            addString(row, t.sujets_rameau(), idx);
        }

        // 23. these_sur_travaux
        row.add(t.these_sur_travaux() != null ? t.these_sur_travaux() : "");

        // 24. titres : autre.0 à 3 (langues autres que fr et en, dans l'ordre de l'export), en, fr
        List<String> titresAutre = titresAutre(t);
        for (int i = 0; i < TITRES_AUTRE; i++) {
            addString(row, titresAutre, i);
        }
        row.add(t.titres() != null && t.titres().get("en") != null ? t.titres().get("en") : "");
        row.add(t.titres() != null && t.titres().get("fr") != null ? t.titres().get("fr") : "");

        StringWriter sw = new StringWriter();
        try (CSVPrinter printer = new CSVPrinter(sw, CSV_FORMAT)) {
            printer.printRecord(row);
            return sw.toString().trim();
        } catch (IOException e) {
            throw new IllegalStateException("Erreur lors de la sérialisation CSV de la thèse", e);
        }
    }

    /**
     * Compte, pour chaque champ, les valeurs qui dépassent les colonnes du CSV. auteurs et
     * etablissements_cotutelle n'ont pas de colonnes : seul le premier auteur figure dans le CSV.
     */
    private void recordTruncations(ExportThesis t) {
        truncationReport.record("auteurs", size(t.auteurs()), 1);
        truncationReport.record("directeurs_these", size(t.directeurs_these()), DIRECTEURS);
        truncationReport.record("ecoles_doctorales", size(t.ecoles_doctorales()), ECOLES_DOCTORALES);
        truncationReport.record("etablissements_cotutelle", size(t.etablissements_cotutelle()), 0);
        truncationReport.record("etablissements_soutenance", size(t.etablissements_soutenance()), ETABLISSEMENTS);
        truncationReport.record("langues", size(t.langues()), LANGUES);
        truncationReport.record("membres_jury", size(t.membres_jury()), MEMBRES_INDICES.length);
        truncationReport.record("oai_set_specs", size(t.oai_set_specs()), 1);
        truncationReport.record("partenaires_recherche", size(t.partenaires_recherche()), PARTENAIRES);
        truncationReport.record("rapporteurs", size(t.rapporteurs()), RAPPORTEURS);
        truncationReport.record("resumes.autre", size(t.resumes() != null ? t.resumes().autre() : null), RESUMES_AUTRE);
        truncationReport.record("sujets.autre", size(t.sujets() != null ? t.sujets().autre() : null), SUJETS_AUTRE);
        truncationReport.record("sujets_rameau", size(t.sujets_rameau()), RAMEAU_INDICES.length);
        truncationReport.record("titres.autre", titresAutre(t).size(), TITRES_AUTRE);
    }

    private static int size(List<?> list) {
        return list != null ? list.size() : 0;
    }

    /**
     * Titres dans les langues autres que fr et en, dans l'ordre de la map (langues par ordre alphabétique).
     */
    private static List<String> titresAutre(ExportThesis t) {
        if (t.titres() == null) {
            return List.of();
        }
        return t.titres().entrySet().stream()
                .filter(e -> !"fr".equals(e.getKey()) && !"en".equals(e.getKey()))
                .map(Map.Entry::getValue)
                .toList();
    }

    private void addPerson(List<String> row, List<ExportPerson> list, int index) {
        if (list != null && index < list.size() && list.get(index) != null) {
            ExportPerson p = list.get(index);
            row.add(p.idref() != null ? p.idref() : "");
            row.add(p.nom() != null ? p.nom() : "");
            row.add(p.prenom() != null ? p.prenom() : "");
        } else {
            row.add(""); row.add(""); row.add("");
        }
    }

    private void addOrg(List<String> row, List<ExportOrganization> list, int index, boolean includeType) {
        if (list != null && index < list.size() && list.get(index) != null) {
            ExportOrganization o = list.get(index);
            row.add(o.idref() != null ? o.idref() : "");
            row.add(o.nom() != null ? o.nom() : "");
            if (includeType) {
                row.add(o.type() != null ? o.type() : "");
            }
        } else {
            row.add(""); row.add("");
            if (includeType) {
                row.add("");
            }
        }
    }

    private void addString(List<String> row, List<String> list, int index) {
        if (list != null && index < list.size() && list.get(index) != null) {
            row.add(list.get(index));
        } else {
            row.add("");
        }
    }
}

package fr.theses.batch.job.writer.csv;

import fr.theses.batch.business.datagouv.model.ExportOrganization;
import fr.theses.batch.business.datagouv.model.ExportPerson;
import fr.theses.batch.business.datagouv.model.ExportResumes;
import fr.theses.batch.business.datagouv.model.ExportSujets;
import fr.theses.batch.business.datagouv.model.ExportThesis;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitaires pour DatagouvThesisCsvLineAggregator.
 */
class DatagouvThesisCsvLineAggregatorTest {

    // Nombre de colonnes de l'en-tête écrit par DatagouvBatchConfiguration.datagouvCsvWriter
    private static final int COLUMN_COUNT = 213;

    private DatagouvThesisCsvLineAggregator aggregator;

    @BeforeEach
    void setUp() {
        aggregator = new DatagouvThesisCsvLineAggregator();
    }

    private static ExportThesis empty() {
        return new ExportThesis(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null
        );
    }

    @Test
    void aggregate_avecChampsRenseignes_genereLigneCsvValide() {
        Map<String, String> titres = new LinkedHashMap<>();
        titres.put("fr", "Titre FR");
        titres.put("en", "Title EN");
        titres.put("es", "Titulo ES");
        ExportThesis export = new ExportThesis(
                "oui",
                new ExportPerson("Dupont", "Jean", "111111111"),
                List.of(new ExportPerson("Dupont", "Jean", "111111111")),
                "cas-1",
                "MONT",
                "2023-06-15",
                List.of(new ExportPerson("Directeur", "Alain", "222222222")),
                "Informatique",
                List.of(new ExportOrganization("ED 166", "333333333", null)),
                null,
                List.of(new ExportOrganization("Université Laval", "999999999", null)),
                List.of(new ExportOrganization("Université de Montpellier", "444444444", null)),
                List.of("fr", "en"),
                List.of(new ExportPerson("Membre0", "Zero", "555555555")),
                "2023ABCD0001",
                List.of("ddc:540"),
                List.of(new ExportOrganization("Partenaire1", "666666666", "Laboratoire")),
                new ExportPerson("President", "Pierre", "777777777"),
                List.of(new ExportPerson("Rapporteur1", "Raoul", "888888888")),
                new ExportResumes("Résumé FR", "Abstract EN", List.of("Resumen ES")),
                "theses.fr",
                "soutenue",
                new ExportSujets(List.of("IA", "Machine Learning"), List.of("AI"), List.of("Inteligencia")),
                List.of("Rameau1", "Rameau2"),
                "non",
                titres,
                "2023-06-20",
                "12345S"
        );

        List<String> cols = columns(aggregator.aggregate(export));

        assertThat(cols).hasSize(COLUMN_COUNT);
        assertThat(String.join(",", cols)).startsWith("oui,111111111,Dupont,Jean,cas-1,MONT,2023-06-15");
        assertThat(cols).contains("Directeur", "IA||Machine Learning", "Laboratoire");
        // titres.autre.0..3, titres.en, titres.fr
        assertThat(cols.subList(COLUMN_COUNT - 6, COLUMN_COUNT))
                .containsExactly("Titulo ES", "", "", "", "Title EN", "Titre FR");
        // La cotutelle n'a pas de colonne dans le format historique
        assertThat(cols).doesNotContain("Université Laval");
    }

    @Test
    void aggregate_avecChampsNulls_produitAutantDeColonnesQueLEnTete() {
        List<String> cols = columns(aggregator.aggregate(empty()));

        assertThat(cols).hasSize(COLUMN_COUNT).containsOnly("");
        assertThat(aggregator.getTruncationReport().hasTruncations()).isFalse();
    }

    @Test
    void aggregate_avecSousChampsNulls_laisseLesCellulesVides() {
        ExportPerson vide = new ExportPerson(null, null, null);
        List<ExportPerson> directeurs = new ArrayList<>();
        directeurs.add(null);
        directeurs.add(vide);

        ExportThesis export = new ExportThesis(
                "oui",
                vide,                                                     // auteur sans valeurs
                null, null, null, null,
                directeurs,                                               // élément null puis personne vide
                null,
                List.of(new ExportOrganization(null, null, null)),        // école doctorale vide
                "2030-01-01",                                             // embargo renseigné
                null, null, null, null, null,
                List.of(),                                                // oai_set_specs vide
                List.of(new ExportOrganization("Labo", null, null)),      // partenaire sans idref ni type
                vide,                                                     // président sans valeurs
                null,
                new ExportResumes(null, null, null),
                null, null,
                new ExportSujets(null, null, null),
                null, null,
                Map.of(),
                null, null
        );

        List<String> cols = columns(aggregator.aggregate(export));

        assertThat(cols).hasSize(COLUMN_COUNT);
        assertThat(cols.get(0)).isEqualTo("oui");
        // auteur (1-3) et directeurs_these.0 / .1 (7-12) entièrement vides
        assertThat(cols.subList(1, 4)).containsOnly("");
        assertThat(cols.subList(7, 13)).containsOnly("");
        assertThat(cols.get(33)).isEqualTo("2030-01-01");
        // oai_set_specs vide
        assertThat(cols.get(85)).isEmpty();
        // partenaires_recherche.0 : idref, nom, type
        assertThat(cols.subList(86, 89)).containsExactly("", "Labo", "");
        // president_jury
        assertThat(cols.subList(110, 113)).containsOnly("");
    }

    @Test
    void aggregate_auDelaDesColonnes_compteLesTroncatures() {
        List<ExportPerson> jury = IntStream.range(0, 15)
                .mapToObj(i -> new ExportPerson("Membre" + i, "P", null)).toList();
        List<ExportPerson> auteurs = List.of(new ExportPerson("A", "1", null), new ExportPerson("B", "2", null));
        List<String> titresAutres = List.of("de", "es", "it", "pt", "nl");
        Map<String, String> titres = new LinkedHashMap<>();
        titres.put("fr", "Titre");
        titresAutres.forEach(l -> titres.put(l, "Titre " + l));

        ExportThesis grosse = new ExportThesis(
                null, auteurs.getFirst(), auteurs, null, null, null, null, null, null, null,
                List.of(new ExportOrganization("Cotutelle", null, null)),
                null, null, jury, null,
                List.of("ddc:540", "ddc:500"),
                null, null, null, null, null, null, null,
                Collections.nCopies(60, "Rameau"),
                null, titres, null, null
        );
        aggregator.aggregate(grosse);
        aggregator.aggregate(grosse);
        aggregator.aggregate(empty());

        assertThat(aggregator.getTruncationReport().lines()).containsExactly(
                "auteurs : 2 thèse(s) au-delà de 1 valeur(s), maximum rencontré 2",
                "etablissements_cotutelle : 2 thèse(s) au-delà de 0 valeur(s), maximum rencontré 1",
                "membres_jury : 2 thèse(s) au-delà de 12 valeur(s), maximum rencontré 15",
                "oai_set_specs : 2 thèse(s) au-delà de 1 valeur(s), maximum rencontré 2",
                "sujets_rameau : 2 thèse(s) au-delà de 55 valeur(s), maximum rencontré 60",
                "titres.autre : 2 thèse(s) au-delà de 4 valeur(s), maximum rencontré 5");
    }

    /**
     * Découpe une ligne CSV en colonnes avec le même format que l'agrégateur.
     */
    private static List<String> columns(String csvLine) {
        try (CSVParser parser = CSVParser.parse(csvLine, CSVFormat.DEFAULT)) {
            return parser.getRecords().getFirst().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

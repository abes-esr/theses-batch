package fr.theses.batch.job.writer.csv;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/**
 * Tests unitaires de DatagouvJsonFlattener.
 */
class DatagouvJsonFlattenerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void flatten_objetsEtTableauxImbriques_cheminsPointes() {
        Map<String, String> values = DatagouvJsonFlattener.flatten(mapper.readTree("""
                {"nnt": "2020PA01",
                 "auteur": {"nom": "Dupont", "prenom": "Jean"},
                 "directeurs_these": [{"nom": "Martin"}, {"nom": "Durand", "idref": "123"}],
                 "sujets_rameau": ["un", "deux"],
                 "titres": {"fr": "Titre", "es": "Título"}}
                """));

        assertThat(values).containsExactly(
                entry("nnt", "2020PA01"),
                entry("auteur.nom", "Dupont"),
                entry("auteur.prenom", "Jean"),
                entry("directeurs_these.0.nom", "Martin"),
                entry("directeurs_these.1.nom", "Durand"),
                entry("directeurs_these.1.idref", "123"),
                entry("sujets_rameau.0", "un"),
                entry("sujets_rameau.1", "deux"),
                entry("titres.fr", "Titre"),
                entry("titres.es", "Título"));
    }

    @Test
    void flatten_nullEtVides_aucuneColonne() {
        Map<String, String> values = DatagouvJsonFlattener.flatten(mapper.readTree("""
                {"embargo": null, "langues": [], "resumes": {}, "status": ""}
                """));

        assertThat(values).containsExactly(entry("status", ""));
    }

    @Test
    void flatten_nombresEtBooleens_enTexte() {
        Map<String, String> values = DatagouvJsonFlattener.flatten(mapper.readTree("""
                {"annee": 2020, "accessible": true}
                """));

        assertThat(values).containsExactly(entry("annee", "2020"), entry("accessible", "true"));
    }
}

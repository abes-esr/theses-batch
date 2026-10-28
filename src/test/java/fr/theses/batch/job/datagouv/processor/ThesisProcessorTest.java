package fr.theses.batch.job.datagouv.processor;

import fr.theses.batch.business.datagouv.model.ExportOrganization;
import fr.theses.batch.business.datagouv.model.ExportPerson;
import fr.theses.batch.business.datagouv.model.ExportThesis;
import fr.theses.batch.business.datagouv.model.Thesis;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitaires du processeur ThesisProcessor pour data.gouv.fr.
 * Les thèses sont lues depuis du JSON au format de l'API /api/v1/batch/theses/recherche/
 * (BatchTheseResponseDto de theses-api-recherche), pour vérifier aussi les noms des champs.
 */
class ThesisProcessorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ThesisProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new ThesisProcessor();
    }

    private static Thesis thesis(String json) {
        return MAPPER.readValue(json, Thesis.class);
    }

    @Test
    void process_avecDonneesCompletes_transformeCorrectement() {
        Thesis thesis = thesis("""
                {"id": "s12345", "nnt": "2023ABCD0001", "doi": "10.1234/test", "numSujet": "12345",
                 "titrePrincipal": "Titre principal",
                 "titres": {"en": "Title in English", "fr": "Titre en français avec saut", "es": "Título", "de": "Titel"},
                 "auteurs": [{"nom": "Dupont", "prenom": "Jean", "ppn": "111111111"},
                             {"nom": "Martin", "prenom": "Julie", "ppn": "121212121"}],
                 "directeurs": [{"nom": "Durand", "prenom": "Marie", "ppn": "222222222"}],
                 "discipline": "Chimie", "dateSoutenance": "15/06/2023", "datePremiereInscriptionDoctorat": "01/10/2020",
                 "etabSoutenance": {"nom": "Université de Montpellier", "ppn": "333333333"},
                 "codeEtab": "MONT",
                 "etabCotutelle": [{"nom": "Université Laval", "ppn": "444444444"}],
                 "ecolesDoctorales": [{"nom": "ED 60", "ppn": "555555555"}],
                 "partenairesRecherche": [{"nom": "Labo Recherche", "ppn": "666666666", "type": "laboratoire"}],
                 "presidentJury": {"nom": "Martin", "prenom": "Paul", "ppn": "777777777"},
                 "rapporteurs": [{"nom": "Rapporteur", "prenom": "Luc", "ppn": "888888888"}],
                 "membresJury": [{"nom": "Examinateur", "prenom": "Anne", "ppn": "999999999"}],
                 "mapSujets": {
                   "fr": [{"keyword": "Rameau 2", "type": "sujetsRameau"},
                          {"keyword": "Rameau 1", "type": "sujetsRameau"},
                          {"keyword": "Polymères", "type": "sujet"}],
                   "en": [{"keyword": "Polymers", "type": "sujet"}],
                   "es": [{"keyword": "Polímeros", "type": "sujet"}]},
                 "langues": ["fr", "en"], "source": "theses.fr", "status": "soutenue", "accessible": "oui",
                 "cas": "cas-1", "dateCines": "20/06/2023", "numSujetSansS": "12345S", "oaiSetNames": ["ddc:540"],
                 "resumes": {"fr": " Énergie $E = \\\\alpha$ ","en": "Abstract in English", "de": "Zusammenfassung auf Deutsch"}}
                """);

        ExportThesis export = processor.process(thesis);

        assertThat(export.accessible()).isEqualTo("oui");
        assertThat(export.source()).isEqualTo("theses.fr");
        assertThat(export.status()).isEqualTo("soutenue");
        assertThat(export.date_soutenance()).isEqualTo("2023-06-15");
        assertThat(export.date_cines()).isEqualTo("2023-06-20");
        assertThat(export.nnt()).isEqualTo("2023ABCD0001");
        assertThat(export.num_sujet_sans_s()).isEqualTo("12345S");

        // Auteurs : liste complète, et premier auteur pour le format historique
        assertThat(export.auteur()).isEqualTo(new ExportPerson("Dupont", "Jean", "111111111"));
        assertThat(export.auteurs()).extracting(ExportPerson::nom).containsExactly("Dupont", "Martin");

        assertThat(export.directeurs_these()).extracting(ExportPerson::nom).containsExactly("Durand");
        assertThat(export.membres_jury()).extracting(ExportPerson::nom).containsExactly("Examinateur");
        assertThat(export.president_jury().nom()).isEqualTo("Martin");

        // Établissements, cotutelles et partenaires (avec leur type)
        assertThat(export.etablissements_soutenance())
                .containsExactly(new ExportOrganization("Université de Montpellier", "333333333", null));
        assertThat(export.etablissements_cotutelle())
                .containsExactly(new ExportOrganization("Université Laval", "444444444", null));
        assertThat(export.partenaires_recherche())
                .containsExactly(new ExportOrganization("Labo Recherche", "666666666", "laboratoire"));
        assertThat(export.ecoles_doctorales()).extracting(ExportOrganization::nom).containsExactly("ED 60");

        // Sujets par langue ; RAMEAU remis dans l'ordre d'origine (l'API les insère en tête de liste)
        assertThat(export.sujets().fr()).containsExactly("Polymères");
        assertThat(export.sujets().en()).containsExactly("Polymers");
        assertThat(export.sujets().autre()).containsExactly("Polímeros");
        assertThat(export.sujets_rameau()).containsExactly("Rameau 1", "Rameau 2");

        // Résumés
        // Antislashs conservés (LaTeX), espaces rognés
        assertThat(export.resumes().fr()).isEqualTo("Énergie $E = \\alpha$");
        assertThat(export.resumes().en()).isEqualTo("Abstract in English");
        assertThat(export.resumes().autre()).containsExactly("Zusammenfassung auf Deutsch");

        // Titres par langue : fr et en en tête, puis les autres par ordre alphabétique, nettoyés
        assertThat(export.titres()).containsExactly(
                Map.entry("fr", "Titre en français\navec saut"),
                Map.entry("en", "Title in English"),
                Map.entry("de", "Titel"),
                Map.entry("es", "Título"));
    }

    @Test
    void process_avecChampsNullsEtValeursParDefaut() {
        ExportThesis export = processor.process(thesis("{\"id\": \"s999\"}"));

        assertThat(export.accessible()).isEqualTo("non");
        assertThat(export.source()).isEqualTo("theses.fr");
        assertThat(export.status()).isEqualTo("en cours");
        assertThat(export.nnt()).isEqualTo("s999");
        assertThat(export.auteur()).isNull();
        assertThat(export.auteurs()).isNull();
        assertThat(export.directeurs_these()).isNull();
        assertThat(export.etablissements_soutenance()).isNull();
        assertThat(export.etablissements_cotutelle()).isNull();
        assertThat(export.date_soutenance()).isNull();
        assertThat(export.sujets()).isNull();
        assertThat(export.sujets_rameau()).isNull();
        assertThat(export.titres()).isNull();
        assertThat(export.resumes()).isNull();
    }

    @Test
    void process_oaiFallbackSurChimie() {
        ExportThesis export = processor.process(thesis("{\"id\": \"s100\", \"discipline\": \"chimie\"}"));

        assertThat(export.oai_set_specs()).containsExactly("ddc:540");
    }

    @Test
    void process_sansTitresParLangue_reprendLeTitrePrincipalEnFrancais() {
        ExportThesis export = processor.process(thesis("""
                {"id": "s101", "titrePrincipal": "Titre seul", "titres": {"en": "   "}}
                """));

        assertThat(export.titres()).containsExactly(Map.entry("fr", "Titre seul"));
    }

    @Test
    void process_listesVidesEtValeursPartielles_produitDesChampsNulls() {
        ExportThesis export = processor.process(thesis("""
                {"id": "s200", "auteurs": [], "directeurs": [], "discipline": "Physique",
                 "dateSoutenance": "2023-06-15",
                 "etabSoutenance": {"ppn": "333333333"},
                 "etabCotutelle": [], "ecolesDoctorales": [], "partenairesRecherche": [],
                 "presidentJury": {"nom": "   ", "prenom": "Paul", "ppn": "777777777"},
                 "rapporteurs": [], "membresJury": [],
                 "mapSujets": {"en": [{"keyword": "AI", "type": "sujet"}], "fr": [{"keyword": null, "type": "sujet"}]},
                 "langues": [], "status": "SOUTENUE", "oaiSetNames": [],
                 "resumes": {"fr": "Résumé", "de": "   ", "it": null}}
                """));

        assertThat(export.nnt()).isEqualTo("s200");
        assertThat(export.status()).isEqualTo("soutenue");
        assertThat(export.auteur()).isNull();
        assertThat(export.auteurs()).isNull();
        assertThat(export.directeurs_these()).isNull();
        assertThat(export.ecoles_doctorales()).isNull();
        assertThat(export.etablissements_cotutelle()).isNull();
        assertThat(export.partenaires_recherche()).isNull();
        assertThat(export.president_jury()).isNull();
        assertThat(export.rapporteurs()).isNull();
        assertThat(export.membres_jury()).isNull();
        assertThat(export.langues()).isNull();
        assertThat(export.sujets_rameau()).isNull();
        assertThat(export.oai_set_specs()).isEmpty();
        // Date dans un format inattendu : conservée telle quelle
        assertThat(export.date_soutenance()).isEqualTo("2023-06-15");
        assertThat(export.etablissements_soutenance())
                .containsExactly(new ExportOrganization(null, "333333333", null));
        assertThat(export.sujets().fr()).isNull();
        assertThat(export.sujets().en()).containsExactly("AI");
        assertThat(export.sujets().autre()).isNull();
        assertThat(export.resumes().fr()).isEqualTo("Résumé");
        assertThat(export.resumes().en()).isNull();
        assertThat(export.resumes().autre()).isNull();
    }

    @Test
    void process_sujetsRameauSeuls_neProduitPasDeSujetsLibres() {
        ExportThesis export = processor.process(thesis("""
                {"id": "s300", "mapSujets": {"fr": [{"keyword": "Chimie macromoléculaire", "type": "sujetsRameau"}]}}
                """));

        assertThat(export.sujets()).isNull();
        assertThat(export.sujets_rameau()).containsExactly("Chimie macromoléculaire");
    }

    @Test
    void process_etablissementSoutenanceVide_estIgnore() {
        ExportThesis export = processor.process(thesis("{\"id\": \"s400\", \"etabSoutenance\": {}}"));

        assertThat(export.etablissements_soutenance()).isNull();
    }
}

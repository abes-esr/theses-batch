package fr.theses.batch.business.datagouv.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import java.util.List;
import java.util.Map;

/**
 * Représente les métadonnées d'une thèse récupérées depuis l'API theses.fr (/api/v1/batch/theses/recherche/).
 * Les noms des composants suivent ceux de la réponse de l'API (BatchTheseResponseDto de theses-api-recherche) ;
 * les alias Jackson couvrent les variations de noms de propriétés existantes.
 */
public record Thesis(
        String id,
        String nnt,
        String doi,
        String numSujet,
        List<Person> auteurs,
        String titrePrincipal,
        // Titres par code de langue (fr, en, es...)
        Map<String, String> titres,
        List<Person> directeurs,
        String discipline,
        String dateSoutenance,
        String datePremiereInscriptionDoctorat,
        Organization etabSoutenance,
        String codeEtab,
        List<Organization> etabCotutelle,

        @JsonAlias({ "ecolesDoctorale", "ecolesDoctorales" })
        List<Organization> ecolesDoctorales,

        @JsonAlias({ "partenairesDeRecherche", "partenairesRecherche" })
        List<Organization> partenairesDeRecherche,

        @JsonAlias({ "president", "presidentJury" })
        Person president,

        List<Person> rapporteurs,

        @JsonAlias({ "examinateurs", "membresJury" })
        List<Person> examinateurs,

        // Mots-clés par code de langue ; les sujets RAMEAU sont rangés sous "fr" avec le type "sujetsRameau"
        Map<String, List<SubjectKeyword>> mapSujets,
        List<String> langues,
        String source,
        String status,
        String accessible,
        String cas,
        String dateCines,
        String numSujetSansS,
        List<String> oaiSetNames,
        Map<String, String> resumes
) {}

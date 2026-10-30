package fr.theses.batch.business.datagouv.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * DTO principal représentant le schéma de thèse complet à exporter en CSV, JSON et JSONL
 * pour la plateforme nationale data.gouv.fr.
 * <p>
 * auteur (premier auteur) est conservé pour la compatibilité du format historique ; auteurs contient
 * la liste complète. titres est indexé par code de langue (fr, en, es...).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExportThesis(
        String accessible,
        ExportPerson auteur,
        List<ExportPerson> auteurs,
        String cas,
        String code_etab,
        String date_soutenance,
        List<ExportPerson> directeurs_these,
        String discipline,
        List<ExportOrganization> ecoles_doctorales,
        String embargo,
        List<ExportOrganization> etablissements_cotutelle,
        List<ExportOrganization> etablissements_soutenance,
        List<String> langues,
        List<ExportPerson> membres_jury,
        String nnt,
        List<String> oai_set_specs,
        List<ExportOrganization> partenaires_recherche,
        ExportPerson president_jury,
        List<ExportPerson> rapporteurs,
        ExportResumes resumes,
        String source,
        String status,
        ExportSujets sujets,
        List<String> sujets_rameau,
        String these_sur_travaux,
        Map<String, String> titres,
        String date_cines,
        String num_sujet_sans_s
) {}

package fr.theses.batch.job.processor;

import fr.theses.batch.business.datagouv.model.ExportOrganization;
import fr.theses.batch.business.datagouv.model.ExportPerson;
import fr.theses.batch.business.datagouv.model.ExportResumes;
import fr.theses.batch.business.datagouv.model.ExportSujets;
import fr.theses.batch.business.datagouv.model.ExportThesis;
import fr.theses.batch.business.datagouv.model.Organization;
import fr.theses.batch.business.datagouv.model.Person;
import fr.theses.batch.business.datagouv.model.SubjectKeyword;
import fr.theses.batch.business.datagouv.model.Thesis;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Processeur chargé de nettoyer, harmoniser et transformer les données d'une thèse
 * du format API brute (Thesis) vers le format d'export historique (ExportThesis).
 */
public class DatagouvThesisProcessor implements ItemProcessor<Thesis, ExportThesis> {

    /**
     * Traite un élément Thesis brut pour générer le DTO d'export ExportThesis.
     *
     * @param t Données brutes reçues de l'API.
     * @return DTO transformé et nettoyé prêt pour l'écriture.
     */
    @Override
    public ExportThesis process(@NonNull Thesis t) {
        // 1. Valeurs par défaut simples et harmonisées
        String cleanSource = t.source() != null ? t.source() : "theses.fr";
        String cleanAccessible = t.accessible() != null ? t.accessible() : "non";
        String cleanStatus = "soutenue".equalsIgnoreCase(t.status()) ? "soutenue" : "en cours";

        // 2. Auteurs : liste complète, et premier auteur seul pour le format historique
        List<ExportPerson> auteurs = persons(t.auteurs());
        ExportPerson auteur = auteurs != null ? auteurs.getFirst() : null;

        // 3. Établissement de soutenance (enveloppé dans une liste pour le format historique)
        List<ExportOrganization> etabs = null;
        if (t.etabSoutenance() != null && (t.etabSoutenance().nom() != null || t.etabSoutenance().ppn() != null)) {
            etabs = List.of(new ExportOrganization(t.etabSoutenance().nom(), t.etabSoutenance().ppn(), null));
        }

        // 4. Langues
        List<String> langues = t.langues() != null && !t.langues().isEmpty() ? t.langues() : null;

        // 5. Président du jury
        ExportPerson president = null;
        if (t.president() != null && t.president().nom() != null && !t.president().nom().trim().isEmpty()) {
            president = new ExportPerson(t.president().nom(), t.president().prenom(), t.president().ppn());
        }

        // 6. Sujets libres par langue et sujets RAMEAU
        List<String> rameaux = new ArrayList<>();
        ExportSujets sujets = buildSujets(t.mapSujets(), rameaux);

        // 7. OAI Set Specs (on utilise en priorité oaiSetNames retourné par l'API, sinon repli sur l'ancienne déduction)
        List<String> oaiSpecs = t.oaiSetNames();
        if ((oaiSpecs == null || oaiSpecs.isEmpty())
                && t.discipline() != null && "chimie".equalsIgnoreCase(t.discipline().trim())) {
            oaiSpecs = List.of("ddc:540");
        }

        // Retourne le DTO d'export complété
        return new ExportThesis(
                cleanAccessible,
                auteur,
                auteurs,
                t.cas(),
                t.codeEtab(),
                reformatDate(t.dateSoutenance()),
                persons(t.directeurs()),
                t.discipline(),
                organizations(t.ecolesDoctorales(), false),
                null, // embargo
                organizations(t.etabCotutelle(), false),
                etabs,
                langues,
                persons(t.examinateurs()),
                t.nnt() != null ? t.nnt() : t.id(),
                oaiSpecs,
                organizations(t.partenairesDeRecherche(), true),
                president,
                persons(t.rapporteurs()),
                buildResumes(t.resumes()),
                cleanSource,
                cleanStatus,
                sujets,
                rameaux.isEmpty() ? null : rameaux,
                "non", // these_sur_travaux
                buildTitres(t),
                reformatDate(t.dateCines()),
                t.numSujetSansS()
        );
    }

    /**
     * Convertit une liste de personnes de l'API, ou null si elle est absente ou vide.
     */
    private static List<ExportPerson> persons(List<Person> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.stream()
                .map(p -> new ExportPerson(p.nom(), p.prenom(), p.ppn()))
                .toList();
    }

    /**
     * Convertit une liste d'organismes de l'API, ou null si elle est absente ou vide.
     *
     * @param includeType true pour reprendre le type d'organisme (partenaires de recherche).
     */
    private static List<ExportOrganization> organizations(List<Organization> list, boolean includeType) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.stream()
                .map(o -> new ExportOrganization(o.nom(), o.ppn(), includeType ? o.type() : null))
                .toList();
    }

    /**
     * Répartit les mots-clés de mapSujets : sujets libres par langue (fr/en/autre) dans le résultat,
     * sujets RAMEAU dans la liste rameaux. Les autres langues sont parcourues par ordre alphabétique.
     */
    private ExportSujets buildSujets(Map<String, List<SubjectKeyword>> mapSujets, List<String> rameaux) {
        List<String> sujetFr = new ArrayList<>();
        List<String> sujetEn = new ArrayList<>();
        List<String> sujetAutre = new ArrayList<>();
        if (mapSujets != null) {
            for (Map.Entry<String, List<SubjectKeyword>> entry : new TreeMap<>(mapSujets).entrySet()) {
                List<String> target = switch (entry.getKey().toLowerCase()) {
                    case "fr" -> sujetFr;
                    case "en" -> sujetEn;
                    default -> sujetAutre;
                };
                for (SubjectKeyword s : entry.getValue() != null ? entry.getValue() : List.<SubjectKeyword>of()) {
                    if (s != null && s.keyword() != null) {
                        (SubjectKeyword.TYPE_RAMEAU.equals(s.type()) ? rameaux : target).add(cleanText(s.keyword()));
                    }
                }
            }
        }
        // L'API insère chaque sujet RAMEAU en tête de liste : on rétablit l'ordre d'origine
        Collections.reverse(rameaux);

        if (sujetFr.isEmpty() && sujetEn.isEmpty() && sujetAutre.isEmpty()) {
            return null;
        }
        return new ExportSujets(
                sujetFr.isEmpty() ? null : sujetFr,
                sujetEn.isEmpty() ? null : sujetEn,
                sujetAutre.isEmpty() ? null : sujetAutre);
    }

    /**
     * Titres par code de langue : fr et en en tête, puis les autres langues par ordre alphabétique.
     * Sans titres par langue, le titre principal est repris comme titre français (format historique).
     */
    private Map<String, String> buildTitres(Thesis t) {
        Map<String, String> titres = new LinkedHashMap<>();
        if (t.titres() != null) {
            Map<String, String> parLangue = new TreeMap<>();
            t.titres().forEach((langue, titre) -> {
                if (titre != null && !titre.isBlank()) {
                    parLangue.put(langue.toLowerCase(), cleanText(titre));
                }
            });
            for (String langue : List.of("fr", "en")) {
                if (parLangue.containsKey(langue)) {
                    titres.put(langue, parLangue.remove(langue));
                }
            }
            titres.putAll(parLangue);
        }
        if (titres.isEmpty() && t.titrePrincipal() != null && !t.titrePrincipal().isBlank()) {
            titres.put("fr", cleanText(t.titrePrincipal()));
        }
        return titres.isEmpty() ? null : titres;
    }

    /**
     * Résumés par langue (fr/en/autre) extraits de la Map de l'API après nettoyage.
     */
    private ExportResumes buildResumes(Map<String, String> resumes) {
        if (resumes == null || resumes.isEmpty()) {
            return null;
        }
        List<String> autreResumes = new ArrayList<>();
        for (Map.Entry<String, String> entry : new TreeMap<>(resumes).entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (value != null && !value.trim().isEmpty() && !"fr".equalsIgnoreCase(key) && !"en".equalsIgnoreCase(key)) {
                autreResumes.add(cleanText(value));
            }
        }
        return new ExportResumes(cleanText(resumes.get("fr")), cleanText(resumes.get("en")),
                autreResumes.isEmpty() ? null : autreResumes);
    }

    /**
     * Reformate une date au format jj/mm/aaaa vers aaaa-mm-jj.
     *
     * @param dateStr Chaîne de date d'origine.
     * @return Chaîne reformatée ou valeur d'origine si non convertible.
     */
    private String reformatDate(String dateStr) {
        if (dateStr == null) {
            return null;
        }
        if (dateStr.matches("\\d{2}/\\d{2}/\\d{4}")) {
            String[] parts = dateStr.split("/");
            return parts[2] + "-" + parts[1] + "-" + parts[0];
        }
        return dateStr;
    }

    /**
     * Nettoie le texte en remplaçant les séparateurs de ligne Unicode inhabituels (LS   et PS  )
     * par des sauts de ligne ordinaires, et en rognant les espaces. Les antislashs sont conservés
     * (LaTeX des résumés, notations) : les writers JSON et CSV les échappent eux-mêmes.
     *
     * @param text Chaîne textuelle brute.
     * @return Chaîne assainie.
     */
    private String cleanText(String text) {
        if (text == null) {
            return null;
        }
        return text.replace(" ", "\n").replace(" ", "\n").trim();
    }
}

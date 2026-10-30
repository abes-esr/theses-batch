package fr.theses.batch.job.reader;

import fr.theses.batch.business.datagouv.model.ThesesResponse;
import fr.theses.batch.business.datagouv.model.Thesis;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.infrastructure.item.ItemReader;
import org.springframework.web.client.RestClient;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ItemReader paginé pour interroger l'API REST de theses.fr.
 * Récupère les thèses par lots successifs via RestClient.
 * <p>
 * L'endpoint batch de theses-api-recherche stabilise l'ordre d'une page à l'autre (même copie de l'index,
 * date de soutenance départagée par idDoc). Par sécurité, si une thèse apparaît malgré tout deux fois ou
 * manque (index modifié pendant l'export, ancienne version de l'API), le reader le détecte : les doublons
 * (même NNT, ou même identifiant à défaut) sont ignorés, et la lecture échoue s'il y a moins de thèses
 * distinctes que le totalHits annoncé par la première page. Les thèses ajoutées pendant l'export
 * (totalHits dépassé) sont acceptées.
 */
@Slf4j
public class DatagouvThesesItemReader implements ItemReader<Thesis> {

    private final RestClient restClient;
    private final int pageSize;
    private final boolean modeTest;
    private static final int MAX_ITEMS_FOR_TEST = 50;

    private int currentDebut = 0;
    private List<Thesis> currentBatch;
    private int currentIndex = 0;
    private boolean isFinished = false;

    private final Set<String> seenKeys = new HashSet<>();
    private long expectedTotal = -1;
    private long readCount = 0;
    private long duplicateCount = 0;
    private boolean completenessChecked = false;

    /**
     * Constructeur pour initialiser le Reader paginé.
     *
     * @param restClient Client HTTP configuré.
     * @param pageSize   Taille d'une page de résultats.
     * @param modeTest   Vrai si le mode test (limité à 50 thèses) est activé.
     */
    public DatagouvThesesItemReader(RestClient restClient, int pageSize, boolean modeTest) {
        this.restClient = restClient;
        this.pageSize = pageSize;
        this.modeTest = modeTest;
    }

    /**
     * Lit l'élément suivant. S'il n'y a plus d'éléments disponibles dans le cache local,
     * interroge l'API pour charger la page suivante.
     * Les thèses déjà lues sur une page précédente sont ignorées.
     *
     * @return Une thèse (Thesis), ou null s'il n'y a plus aucun élément à lire.
     * @throws IllegalStateException en fin de lecture, si l'export est vide ou s'il manque des thèses
     *                               par rapport au totalHits.
     */
    @Override
    public Thesis read() {
        while (hasCurrentItem()) {
            Thesis thesis = currentBatch.get(currentIndex++);
            String key = thesis.nnt() != null && !thesis.nnt().isBlank() ? thesis.nnt() : thesis.id();
            if (key == null || seenKeys.add(key)) {
                readCount++;
                return thesis;
            }
            duplicateCount++;
            log.debug("Thèse {} déjà lue sur une page précédente : ignorée.", key);
        }
        checkCompleteness();
        return null;
    }

    /**
     * Charge la page suivante si la page courante est épuisée.
     *
     * @return true s'il reste une thèse à lire dans la page courante.
     */
    private boolean hasCurrentItem() {
        if (currentBatch != null && currentIndex < currentBatch.size()) {
            return true;
        }
        if (isFinished) {
            return false;
        }
        fetchNextBatch();
        currentIndex = 0;
        return currentBatch != null && !currentBatch.isEmpty();
    }

    /**
     * Vérifie, une seule fois en fin de lecture, que toutes les thèses annoncées ont été lues.
     * Un export vide est aussi refusé. Ignoré en mode test, où la lecture est volontairement limitée.
     */
    private void checkCompleteness() {
        if (completenessChecked) {
            return;
        }
        completenessChecked = true;
        log.info("Lecture terminée : {} thèses distinctes lues, {} doublons ignorés, totalHits initial : {}.",
                readCount, duplicateCount, expectedTotal);
        // Un export vide écraserait les fichiers publiés sur data.gouv.fr : mieux vaut échouer et retenter
        if (!modeTest && readCount == 0) {
            throw new IllegalStateException("Export vide : l'API theses.fr n'a renvoyé aucune thèse soutenue.");
        }
        if (!modeTest && readCount < expectedTotal) {
            throw new IllegalStateException("Export incomplet : " + readCount + " thèses distinctes lues sur "
                    + expectedTotal + " annoncées par l'API theses.fr (" + duplicateCount + " doublons ignorés).");
        }
    }

    /**
     * Récupère la page suivante de thèses depuis l'API REST.
     * En mode test, s'arrête dès que la limite configurée (50) est atteinte.
     */
    private void fetchNextBatch() {
        if (modeTest && currentDebut >= MAX_ITEMS_FOR_TEST) {
            log.info("Mode test actif : arrêt de la récupération après {} thèses.", MAX_ITEMS_FOR_TEST);
            isFinished = true;
            currentBatch = null;
            return;
        }

        int nombre = modeTest ? Math.min(pageSize, MAX_ITEMS_FOR_TEST - currentDebut) : pageSize;

        log.info("Récupération des thèses depuis l'API theses.fr : debut={}, nombre={}", currentDebut, nombre);

        ThesesResponse response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/theses/recherche/")
                        .queryParam("q", "*")
                        .queryParam("debut", currentDebut)
                        .queryParam("nombre", nombre)
                        .queryParam("tri", "dateAsc")
                        .queryParam("filtres", "[Statut=\"soutenue\"]")
                        .queryParam("viewFull", "true")
                        .build())
                .retrieve()
                .body(ThesesResponse.class);

        if (response == null || response.theses() == null || response.theses().isEmpty()) {
            log.info("Fin de la récupération. Aucun résultat ou liste vide renvoyée.");
            isFinished = true;
            currentBatch = null;
            return;
        }

        currentBatch = response.theses();
        currentDebut += currentBatch.size();
        if (expectedTotal < 0) {
            expectedTotal = response.totalHits();
        }

        log.info("Page reçue avec succès. Nombre d'éléments: {}, TotalHits estimé: {}",
                currentBatch.size(), response.totalHits());

        // Si nous avons récupéré moins d'éléments que la taille de page demandée,
        // nous sommes arrivés à la fin du jeu de données.
        if (currentBatch.size() < nombre) {
            log.info("Fin de la récupération de l'API (dernière page incomplète).");
            isFinished = true;
        }
    }
}

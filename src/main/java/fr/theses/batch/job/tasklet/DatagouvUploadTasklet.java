package fr.theses.batch.job.tasklet;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.jspecify.annotations.NonNull;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tasklet Spring Batch chargée de téléverser les fichiers CSV, JSON, JSONL et JSONL.gz générés
 * vers data.gouv.fr via l'API Multipart.
 * <p>
 * Un fichier plus gros que chunkSize est envoyé en plusieurs morceaux (protocole d'upload
 * découpé de udata) : seul un morceau à la fois est chargé en mémoire, quelle que soit la
 * taille du fichier.
 * <p>
 * Si le mode test ou upload-enabled=false est configuré, l'upload est ignoré : un export
 * de test (limité à 50 thèses) ne doit jamais remplacer les ressources publiées.
 * <p>
 * Les 4 ressources sont remplacées l'une après l'autre, sans nouvelle tentative : un échec en cours
 * de route laisse les ressources déjà envoyées à jour et les suivantes à leur version précédente, et
 * le log d'erreur indique lesquelles ont été publiées. Le job se termine alors en échec, ce qui ne
 * compte pas comme une publication : le cron suivant relance l'export complet et les 4 envois.
 */
@Slf4j
public class DatagouvUploadTasklet implements Tasklet {

    private static final String UPLOAD_PATH = "/api/1/datasets/{datasetId}/resources/{resourceId}/upload/";

    private final RestClient restClient;
    private final String baseUrl;
    private final String datasetId;
    private final String csvResourceId;
    private final String jsonResourceId;
    private final String jsonlResourceId;
    private final String jsonlGzResourceId;
    private final String apiKey;
    private final String outputDir;
    private final boolean uploadEnabled;
    private final boolean modeTest;
    private final String runTimestamp;
    private final int chunkSize;

    /**
     * Constructeur initialisant les dépendances et les identifiants de ressources data.gouv.fr.
     *
     * @param restClient         Client HTTP configuré pour l'API data.gouv.fr.
     * @param baseUrl            URL de base de data.gouv.fr (ex: https://www.data.gouv.fr, https://demo.data.gouv.fr).
     * @param datasetId          Identifiant du jeu de données cible sur data.gouv.fr.
     * @param csvResourceId      Identifiant de la ressource pour le fichier CSV.
     * @param jsonResourceId     Identifiant de la ressource pour le fichier JSON.
     * @param jsonlResourceId    Identifiant de la ressource pour le fichier JSONL.
     * @param jsonlGzResourceId  Identifiant de la ressource pour le fichier JSONL compressé.
     * @param apiKey             Clé d'API data.gouv.fr.
     * @param outputDir          Répertoire local contenant les fichiers générés.
     * @param uploadEnabled      Indique si le téléversement effectif est activé.
     * @param modeTest           Vrai si le mode test est activé : le téléversement est alors toujours ignoré.
     * @param runTimestamp       Horodatage du run au format yyyy-MM-dd-HH-mm-ss.
     * @param chunkSize          Taille maximale en octets d'un morceau envoyé.
     */
    public DatagouvUploadTasklet(RestClient restClient, String baseUrl, String datasetId, String csvResourceId,
                         String jsonResourceId, String jsonlResourceId, String jsonlGzResourceId,
                         String apiKey, String outputDir, boolean uploadEnabled, boolean modeTest, String runTimestamp,
                         int chunkSize) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("La taille des morceaux d'upload doit être positive : " + chunkSize);
        }
        this.restClient = restClient;
        // Sans « / » final : UPLOAD_PATH commence déjà par « / »
        this.baseUrl = baseUrl == null ? null : stripTrailingSlashes(baseUrl);
        this.datasetId = datasetId;
        this.csvResourceId = csvResourceId;
        this.jsonResourceId = jsonResourceId;
        this.jsonlResourceId = jsonlResourceId;
        this.jsonlGzResourceId = jsonlGzResourceId;
        this.apiKey = apiKey;
        this.outputDir = outputDir;
        this.uploadEnabled = uploadEnabled;
        this.modeTest = modeTest;
        this.runTimestamp = runTimestamp;
        this.chunkSize = chunkSize;
    }

    private static String stripTrailingSlashes(String url) {
        int end = url.length();
        while (end > 0 && url.charAt(end - 1) == '/') {
            end--;
        }
        return url.substring(0, end);
    }

    /**
     * Exécute le téléversement des fichiers générés.
     * Les fichiers locaux portent le préfixe {timestamp}_datagouv_results, tandis qu'ils
     * sont téléversés vers data.gouv.fr sous le nom fixe theses-soutenues.{type}.
     *
     * @param contribution Contribution d'étape.
     * @param chunkContext Contexte du chunk.
     * @return RepeatStatus.FINISHED une fois le traitement terminé.
     * @throws IOException si un fichier ne peut pas être lu.
     */
    @Override
    public RepeatStatus execute(@NonNull StepContribution contribution, @NonNull ChunkContext chunkContext) throws IOException {
        if (modeTest) {
            log.info("Mode test actif : téléversement vers data.gouv.fr ignoré, quelle que soit la valeur de app.datagouv.upload-enabled.");
            log.info("Les 4 fichiers d'export sont conservés localement (rangés en fin de job dans datagouv/test).");
            return RepeatStatus.FINISHED;
        }
        if (!uploadEnabled) {
            log.info("Téléversement vers data.gouv.fr désactivé (app.datagouv.upload-enabled = false).");
            log.info("Les 4 fichiers d'export sont conservés localement (rangés en fin de job dans datagouv/test).");
            return RepeatStatus.FINISHED;
        }

        // Configuration et fichiers vérifiés avant le premier envoi : rien n'est envoyé si l'un d'eux manque
        checkReadyToUpload();
        log.info("Début du téléversement des exports vers data.gouv.fr...");

        // Format -> identifiant de ressource, dans l'ordre d'envoi
        Map<String, String> resources = new LinkedHashMap<>();
        resources.put("csv", csvResourceId);
        resources.put("json", jsonResourceId);
        resources.put("jsonl", jsonlResourceId);
        resources.put("jsonl.gz", jsonlGzResourceId);

        List<String> published = new ArrayList<>();
        for (Map.Entry<String, String> resource : resources.entrySet()) {
            String extension = resource.getKey();
            try {
                uploadFile(extension.toUpperCase(), resource.getValue(), runTimestamp + "_datagouv_results." + extension,
                        "theses-soutenues." + extension);
            } catch (RuntimeException | IOException e) {
                List<String> notPublished = new ArrayList<>(resources.keySet());
                notPublished.removeAll(published);
                log.error("Échec du téléversement du fichier {} : publication partielle sur data.gouv.fr. "
                                + "Ressources mises à jour : {} ; ressources restées à leur version précédente : {}. "
                                + "Le prochain lancement refera l'export et les 4 envois.",
                        extension, published.isEmpty() ? "aucune" : published, notPublished);
                throw e;
            }
            published.add(extension);
        }

        log.info("Tous les fichiers ont été téléversés avec succès sur data.gouv.fr.");
        return RepeatStatus.FINISHED;
    }

    /**
     * Vérifie que la configuration data.gouv.fr est complète et que les 4 fichiers existent.
     *
     * @throws IllegalStateException en listant tout ce qui manque.
     */
    private void checkReadyToUpload() {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("DATA_GOUV_BASE_URL", baseUrl);
        settings.put("DATA_GOUV_API_KEY", apiKey);
        settings.put("DATA_GOUV_DATASET_ID", datasetId);
        settings.put("DATA_GOUV_CSV_RESOURCE_ID", csvResourceId);
        settings.put("DATA_GOUV_JSON_RESOURCE_ID", jsonResourceId);
        settings.put("DATA_GOUV_JSONL_RESOURCE_ID", jsonlResourceId);
        settings.put("DATA_GOUV_JSONL_GZ_RESOURCE_ID", jsonlGzResourceId);

        List<String> missing = new ArrayList<>();
        settings.forEach((name, value) -> {
            if (value == null || value.isBlank()) {
                missing.add(name + " non renseignée");
            }
        });
        for (String extension : List.of("csv", "json", "jsonl", "jsonl.gz")) {
            File file = new File(outputDir, runTimestamp + "_datagouv_results." + extension);
            if (!file.exists()) {
                missing.add("fichier " + file.getAbsolutePath() + " absent");
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Téléversement vers data.gouv.fr impossible, aucun fichier envoyé : "
                    + String.join(", ", missing));
        }
    }

    /**
     * Téléverse un fichier spécifique vers data.gouv.fr, en une requête ou en plusieurs morceaux.
     *
     * @param formatName       Nom lisible du format (ex: "CSV").
     * @param resourceId       ID de ressource data.gouv.fr cible.
     * @param localFileName    Nom du fichier local dans le répertoire de sortie.
     * @param targetUploadName Nom sous lequel le fichier doit être téléversé.
     * @throws IOException si le fichier ne peut pas être lu.
     */
    private void uploadFile(String formatName, String resourceId, String localFileName, String targetUploadName) throws IOException {
        File file = new File(outputDir, localFileName);
        if (!file.exists()) {
            throw new IllegalStateException("Le fichier " + file.getAbsolutePath() + " à téléverser n'existe pas.");
        }

        long size = file.length();
        int totalParts = (int) Math.max(1, (size + chunkSize - 1) / chunkSize);
        log.info("Téléversement du fichier {} ({}) vers data.gouv.fr sous le nom {}. Taille: {} octets, {} morceau(x).",
                localFileName, formatName, targetUploadName, size, totalParts);

        if (totalParts == 1) {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", new NamedFileSystemResource(file, targetUploadName));
            post(resourceId, body);
        } else {
            uploadInChunks(file, resourceId, targetUploadName, totalParts);
        }

        log.info("Téléversement réussi pour le fichier {} sous le nom {}.", localFileName, targetUploadName);
    }

    /**
     * Envoie le fichier morceau par morceau, puis demande à data.gouv.fr de les réassembler :
     * chaque morceau porte uuid, partindex, totalparts et chunksize ; la requête finale, sans
     * fichier, déclenche l'assemblage et remplace le fichier de la ressource.
     */
    private void uploadInChunks(File file, String resourceId, String targetUploadName, int totalParts) throws IOException {
        String uuid = UUID.randomUUID().toString();
        byte[] buffer = new byte[chunkSize];
        try (InputStream in = Files.newInputStream(file.toPath())) {
            for (int partIndex = 0; partIndex < totalParts; partIndex++) {
                int read = in.readNBytes(buffer, 0, chunkSize);
                byte[] part = read == chunkSize ? buffer : Arrays.copyOf(buffer, read);

                MultiValueMap<String, Object> body = chunkFields(uuid, targetUploadName, totalParts);
                body.add("partindex", partIndex);
                body.add("chunksize", read);
                body.add("file", new NamedByteArrayResource(part, targetUploadName));
                post(resourceId, body);
                log.debug("Morceau {}/{} de {} envoyé ({} octets).", partIndex + 1, totalParts, targetUploadName, read);
            }
        }
        post(resourceId, chunkFields(uuid, targetUploadName, totalParts));
    }

    /**
     * Champs communs à toutes les requêtes d'un upload découpé.
     */
    private static MultiValueMap<String, Object> chunkFields(String uuid, String targetUploadName, int totalParts) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("uuid", uuid);
        body.add("filename", targetUploadName);
        body.add("totalparts", totalParts);
        return body;
    }

    private void post(String resourceId, MultiValueMap<String, Object> body) {
        restClient.post()
                .uri(baseUrl + UPLOAD_PATH, datasetId, resourceId)
                .header("X-API-KEY", apiKey)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * Extension de FileSystemResource permettant de personnaliser le nom de fichier
     * transmis dans l'en-tête Content-Disposition sans dépendance à Reactive Streams.
     */
    private static class NamedFileSystemResource extends FileSystemResource {
        private final String customFilename;

        public NamedFileSystemResource(File file, String customFilename) {
            super(file);
            this.customFilename = customFilename;
        }

        @Override
        @NonNull
        public String getFilename() {
            return customFilename;
        }
    }

    /**
     * Morceau de fichier en mémoire, transmis comme partie "file" sous le nom du fichier complet.
     */
    private static class NamedByteArrayResource extends ByteArrayResource {
        private final String customFilename;

        public NamedByteArrayResource(byte[] content, String customFilename) {
            super(content);
            this.customFilename = customFilename;
        }

        @Override
        @NonNull
        public String getFilename() {
            return customFilename;
        }
    }
}

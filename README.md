# theses-batch

Un ensemble unifié d'utilitaires batch relatifs aux thèses pour l'Abes.

## 📋 Liste des jobs disponibles

| Identifiant CLI        | Nom du job Spring Batch | Description                                                                                      |
| ---------------------- | ----------------------- | ------------------------------------------------------------------------------------------------ |
| `anr`                  | `anrJob`                | Extraction de mentions de financement ANR dans les fichiers PDF de thèses                        |
| `datagouv` (ou `dump`) | `thesesDumpDataGouvJob` | Exportation complète des thèses soutenues (CSV, JSON, JSONL, JSONL.gz) et téléversement vers data.gouv.fr |

---

## 🏗️ Architecture et Système Docker de l'Abes

Le projet utilise l'architecture standard de l'Abes pour les conteneurs Batch :

- **Image de base :** Rocky Linux 9 avec OpenJDK 21, `cronie` et `procps`.
- **Une image, deux conteneurs**, chacun avec son point d'entrée. L'image n'en définit aucun par défaut : il doit être précisé par `entrypoint` dans `docker-compose.yml` (voir le dépôt `theses-batch-docker`) :
  - **`/docker-entrypoint-anr.sh`** : aucun cron ; le conteneur reste actif en attendant un lancement manuel (`docker exec`).
  - **`/docker-entrypoint-datagouv.sh`** : installe la crontab à partir de `${THESES_BATCH_DATAGOUV_CRON}` puis démarre `crond -n`. Les sorties de cron sont redirigées vers `/proc/1/fd/1` et `/proc/1/fd/2` pour un affichage direct dans les logs Docker.
- Les deux points d'entrée répercutent les variables d'environnement dans `/etc/environment` (pour cronie et `docker exec`).
- Aucun batch n'est lancé au démarrage du conteneur.

---

## 🚀 Utilisation avec Docker

Le déploiement se fait avec le dépôt `theses-batch-docker` (deux conteneurs, scripts `scripts/run-anr.sh`, `scripts/run-datagouv.sh` et `scripts/run-batch.sh`).
Dans un conteneur déjà démarré, un batch se lance avec :

```bash
docker exec theses-batch-anr /scripts/theses-batch-anr.sh
docker exec theses-batch-datagouv /scripts/theses-batch-datagouv.sh --app.datagouv.force-run=true
```

Un échec du batch se termine par un code de sortie non nul et une erreur dans les logs ; le conteneur continue de tourner.

---

## 💻 Développement et Lancement Local

### Prérequis

- Java 21
- Maven 3.9+

### Commandes Maven

- Lancer le batch **ANR** :
  ```bash
  mvn spring-boot:run -Dspring-boot.run.arguments="anr"
  ```
- Lancer le batch **Datagouv** (mode test 50 thèses) :
  ```bash
  mvn spring-boot:run -Dspring-boot.run.arguments="datagouv --app.mode-test=true"
  ```
- Compiler le JAR exécutable :
  ```bash
  mvn clean package -DskipTests
  ```
- Exécuter le JAR produit :
  ```bash
  java -jar target/theses-batch-1.0.0-SNAPSHOT.jar anr
  java -jar target/theses-batch-1.0.0-SNAPSHOT.jar datagouv
  ```

---

## ⚙️ Détails des Jobs

### 1. `anrJob` : Extraction ANR

Extraction des métadonnées de thèses dont le corpus contient des expressions suggérant l'existence d'un financement par l'ANR.

### Colonnes extraites

La variable d'environnement `THESES_BATCH_ANR_WRITERS_HEADER` permet d'extraire les données selon les clés suivantes (à sélectionner et définir dans le fichier .env) :

| Clé                     | Description                                                        |
| ----------------------- | ------------------------------------------------------------------ |
| `file_path`             | Chemin du fichier PDF analysé                                      |
| `file_name`             | Nom du fichier PDF analysé                                         |
| `page_number`           | Numéro de la page où la mention a été trouvée                      |
| `match_value`           | Valeur exacte de l'expression reconnue par le pattern de recherche |
| `preceding_context`     | Contexte : les x caractères précédant la mention                   |
| `following_context`     | Contexte : les x caractères suivant la mention                     |
| `analyzed_pages_number` | Nombre de pages réellement analysées dans le document              |
| `total_pages`           | Nombre total de pages du document                                  |
| `processing_time`       | Durée de traitement du document                                    |
| `nnt`                   | Numéro National de Thèse de la thèse                               |
| `doi`                   | DOI (Digital Object Identifier) de la thèse                        |
| `defense_date`          | Date de soutenance de la thèse                                     |
| `error_message`         | Message d'erreur rencontré lors du traitement, le cas échéant      |

### 2. `thesesDumpDataGouvJob` : Export et Téléversement data.gouv.fr

Interroge l'API `theses.fr` de façon paginée, écrit le `.json` et le `.jsonl`, génère le `.csv` en aplatissant le `.jsonl`, compresse le `.jsonl` en `.jsonl.gz`, puis téléverse ces quatre fichiers vers data.gouv.fr (en morceaux de `DATA_GOUV_UPLOAD_CHUNK_SIZE` pour les gros fichiers).

- **CSV = JSON aplati :** un attribut d'objet devient la colonne `objet.attribut`, un élément de tableau la colonne `tableau.index` (ex : `directeurs_these.0.nom`, `titres.fr`). Les colonnes sont l'ensemble des chemins rencontrés dans l'export, triés par ordre lexicographique comme dans le format historique : aucune valeur n'est perdue, et le nombre de colonnes dépend des données de l'export.

- **Règle des 6 mois :** Le batch ne s'exécute que si la dernière publication réussie sur data.gouv.fr date de plus de 6 mois. Les exports en mode test ou sans téléversement (`DATA_GOUV_UPLOAD_ENABLED=false`) ne comptent pas.
- **Forçage manuel :** `FORCE_RUN=true` permet de contourner la règle des 6 mois.
- **Mode test :** `MODE_TEST=true` limite l'export à 50 thèses sans délai d'attente.
- **Téléversement :** Activé avec `DATA_GOUV_UPLOAD_ENABLED=true`, l'URL `DATA_GOUV_BASE_URL` (`https://www.data.gouv.fr` en production, `https://demo.data.gouv.fr` pour tester) et les clés d'API configurées. Toujours ignoré en mode test (`MODE_TEST=true`).

# theses-batch

Un ensemble unifié d'utilitaires batch relatifs aux thèses pour l'Abes.

## 📋 Liste des jobs disponibles

| Identifiant CLI        | Nom du job Spring Batch | Description                                                                                      |
| ---------------------- | ----------------------- | ------------------------------------------------------------------------------------------------ |
| `anr`                  | `anrJob`                | Extraction de mentions de financement ANR dans les fichiers PDF de thèses                        |
| `datagouv`             | `thesesDumpDataGouvJob` | Exportation complète des thèses soutenues (CSV, JSON, JSONL, JSONL.gz) et téléversement vers data.gouv.fr |

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
  mvn spring-boot:run -Dspring-boot.run.arguments="datagouv --app.datagouv.mode-test=true"
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

- **CSV = JSON aplati :** un attribut d'objet devient la colonne `objet.attribut`, un élément de tableau la colonne `tableau.index` (ex : `directeurs_these.0.nom`, `titres.fr`). Les colonnes sont l'ensemble des chemins rencontrés dans l'export, triés par ordre lexicographique (`membres_jury.0`, `membres_jury.1`, `membres_jury.10`, `membres_jury.2`...) : aucune valeur n'est perdue.
  - **Le jeu de colonnes n'est pas figé :** il dépend des données exportées (nombre maximal de membres du jury, de directeurs, langues des titres...). Il diffère de l'ancien CSV publié (213 colonnes fixes) et peut varier d'une publication à l'autre. Les colonnes doivent donc être lues par leur nom, pas par leur position. Le JSON et le JSONL, eux, ont une structure stable.

- **Fichiers produits** (dans `OUTPUT_DIR/datagouv`) :
  - `tmp/` : fichiers du run en cours d'écriture ;
  - `success/` : uniquement les fichiers (et le log) de la dernière publication réussie sur data.gouv.fr ;
  - `test/` : uniquement les fichiers du dernier run réussi sans publication (`MODE_TEST=true` ou `DATA_GOUV_UPLOAD_ENABLED=false`), pour ne jamais remplacer ceux de `success/` ;
  - `errors/` : fichiers et logs de tous les runs en échec, vidé à chaque publication réussie. Les restes d'un run interrompu sans fin de job (conteneur arrêté, OOM) y sont déplacés au run suivant.
  - Les logs de chaque passage du cron (`{timestamp}_batch.log`, à la racine de `OUTPUT_DIR`) sont supprimés au-delà de `THESES_BATCH_DATAGOUV_LOG_RETENTION` (30 jours par défaut).

- **Règle des 6 mois :** Le batch ne s'exécute que si la dernière publication réussie sur data.gouv.fr date de plus de 6 mois. Tout l'historique est parcouru ; sans aucune publication réussie, le batch se lance. Les exports en mode test ou sans téléversement (`DATA_GOUV_UPLOAD_ENABLED=false`) ne comptent pas.
  - **Conséquence :** avec `DATA_GOUV_UPLOAD_ENABLED=false`, aucune exécution ne compte comme publication : l'export complet est relancé à chaque passage du cron (tous les jours par défaut).
  - **En cas d'échec** (API theses.fr, téléversement interrompu...), le run ne compte pas comme publication : le cron suivant relance l'export complet et les 4 envois.
- **Forçage manuel :** `FORCE_RUN=true` permet de contourner la règle des 6 mois. Un avertissement est loggué à chaque run forcé : laissé dans l'environnement du conteneur, il relance l'export à chaque passage du cron. Pour un lancement ponctuel, préférer `--app.datagouv.force-run=true` en argument.
- **Mode test :** `MODE_TEST=true` limite l'export à 50 thèses sans délai d'attente.
- **Téléversement :** Activé avec `DATA_GOUV_UPLOAD_ENABLED=true`, l'URL `DATA_GOUV_BASE_URL` (`https://www.data.gouv.fr` en production, `https://demo.data.gouv.fr` pour tester) et les clés d'API configurées. Toujours ignoré en mode test (`MODE_TEST=true`).

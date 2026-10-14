# theses-batch

Un ensemble d'utilitaires batch relatifs aux thèses.

## Liste des jobs disponibles

| Nom du job | Description |
|---|---|
| anrJob | Extraction de mentions de financement ANR |

## anrJob : Extraction de mentions de financement ANR

Extraction des métadonnées de thèses dont le corpus contient des expressions suggérant l'existence d'un financement par l'ANR.

### Colonnes extraites

La variable d'environnement `THESES_BATCH_ANR_WRITERS_HEADER` permet d'extraire les données selon les clés suivantes (à sélectionner et définir dans le fichier .env) :

| Clé | Description |
|---|---|
| `file_path` | Chemin du fichier PDF analysé |
| `file_name` | Nom du fichier PDF analysé |
| `page_number` | Numéro de la page où la mention a été trouvée |
| `match_value` | Valeur exacte de l'expression reconnue par le pattern de recherche |
| `preceding_context` | Contexte : les x caractères précédant la mention |
| `following_context` | Contexte : les x caractères suivant la mention |
| `analyzed_pages_number` | Nombre de pages réellement analysées dans le document |
| `total_pages` | Nombre total de pages du document |
| `processing_time` | Durée de traitement du document |
| `nnt` | Numéro National de Thèse de la thèse |
| `doi` | DOI (Digital Object Identifier) de la thèse |
| `defense_date` | Date de soutenance de la thèse |
| `error_message` | Message d'erreur rencontré lors du traitement, le cas échéant |

### Lancement du batch
Depuis l'hôte :
```bash
docker exec theses-batch /scripts/theses-batch.sh
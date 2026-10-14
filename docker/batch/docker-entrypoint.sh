#!/bin/bash

# Recopie l'environnement du conteneur dans /etc/environment : les tâches cron ne
# reçoivent pas les variables passées au conteneur (docker run -e, .env), mais cron
# relit ce fichier (pam_env) à chaque exécution. Une variable par ligne, valeur entre
# guillemets pour conserver les espaces.
env | sed 's/^\([^=]*\)=\(.*\)$/\1="\2"/' > /etc/environment

# Si le conteneur est démarré avec "batch" ou RUN_BATCH=true, lancer le batch
if [[ "$1" == "batch" ]] || [[ "${RUN_BATCH}" == "true" ]]; then
    exec /scripts/theses-batch.sh "${@:2}"
else
    # Sinon, garder le conteneur actif pour debug/exec
    echo "Theses batch container ready."
    echo "Use 'docker exec <container> /scripts/theses-batch.sh' to start the batch."
    exec tail -f /dev/null
fi

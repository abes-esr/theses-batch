#!/bin/bash

# Recopie l'environnement du conteneur dans /etc/environment : les tâches cron ne
# reçoivent pas les variables passées au conteneur (docker run -e, .env), mais cron
# relit ce fichier (pam_env) à chaque exécution. Une variable par ligne, valeur entre
# guillemets pour conserver les espaces.
env | sed 's/^\([^=]*\)=\(.*\)$/\1="\2"/' > /etc/environment

# Exécute la commande passée en CMD
exec "$@"

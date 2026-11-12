#!/bin/bash
set -e

# ===================================================================
# docker-entrypoint-datagouv.sh : Point d'entrée dédié au conteneur Datagouv
# Maintient le conteneur actif en permanence avec cronie (supervision WUD)
# et prêt pour les exécutions manuelles via ./scripts/run-datagouv.sh (docker exec).
# ===================================================================

export THESES_BATCH_DATAGOUV_CRON=${THESES_BATCH_DATAGOUV_CRON:-'0 2 * * *'}

# 1. Recopie l'environnement du conteneur dans /etc/environment pour cronie
env | sed 's/^\([^=]*\)=\(.*\)$/\1="\2"/' > /etc/environment
echo "LANG=fr_FR.UTF-8" >> /etc/environment
echo "LC_ALL=fr_FR.UTF-8" >> /etc/environment

# 2. Génération et installation de la crontab Datagouv avec cronie (politique Abes)
# Hors de /etc/cron.d : crond y attend une colonne utilisateur ; la ligne est installée comme crontab de root
echo "-> Génération et installation de la crontab Datagouv (${THESES_BATCH_DATAGOUV_CRON}) :"
echo "${THESES_BATCH_DATAGOUV_CRON} cd /scripts/ && /scripts/theses-batch-datagouv.sh 1>/proc/1/fd/1 2>/proc/1/fd/2" > /etc/theses-batch.crontab
cat /etc/theses-batch.crontab
crontab /etc/theses-batch.crontab

# 3. Si une commande est transmise au conteneur (docker run, ou "command" du compose), elle remplace crond
if [ $# -gt 0 ]; then
    exec "$@"
fi

# 4. Maintien du conteneur en cours d'exécution permanent avec cronie pour WUD
echo "-> Conteneur theses-batch-datagouv actif avec cronie et supervisé par WUD."
echo "-> Pour lancer le traitement Datagouv à tout moment : ./scripts/run-datagouv.sh"
exec crond -n

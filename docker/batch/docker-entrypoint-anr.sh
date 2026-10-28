#!/bin/bash
set -e

# ===================================================================
# docker-entrypoint-anr.sh : Point d'entrée dédié au conteneur ANR
# Maintient le conteneur actif en permanence (supervision WUD)
# et prêt pour les exécutions manuelles via ./scripts/run-anr.sh (docker exec).
# ===================================================================

# 1. Recopie l'environnement du conteneur dans /etc/environment
env | sed 's/^\([^=]*\)=\(.*\)$/\1="\2"/' > /etc/environment
echo "LANG=fr_FR.UTF-8" >> /etc/environment
echo "LC_ALL=fr_FR.UTF-8" >> /etc/environment

# 2. Si une commande spécifique est transmise au conteneur
if [ $# -gt 0 ] && [ "$1" != "tail" ]; then
    exec "$@"
fi

# 3. Maintien du conteneur en cours d'exécution permanent pour WUD
echo "-> Conteneur theses-batch-anr actif et supervisé par WUD (aucun cron interne)."
echo "-> Pour lancer le traitement ANR à tout moment : ./scripts/run-anr.sh"
exec tail -f /dev/null

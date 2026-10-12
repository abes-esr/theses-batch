#!/bin/bash

# Réglage de /etc/environment pour que les scripts s'exécutent avec les bonnes variables d'env
echo "$(env)
LANG=fr_FR.UTF-8" > /etc/environment

# Exécute la commande passée en CMD
exec "$@"

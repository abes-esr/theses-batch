#!/bin/bash
# Script de lancement dédié au batch Datagouv (utilisé par cronie et l'utilisateur)
exec /scripts/theses-batch.sh datagouv "$@"

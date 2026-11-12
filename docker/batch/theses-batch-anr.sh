#!/bin/bash
# Script de lancement dédié au batch ANR (utilisé par cronie et l'utilisateur)
exec /scripts/theses-batch.sh anr "$@"

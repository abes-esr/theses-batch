#!/bin/bash

# Mémoire de la JVM, surchargeable par l'environnement du conteneur (.env, docker run -e) :
# - 6 Go Par défaut pour 8 threads de traitement
# - THESES_BATCH_JAVA_XMX : heap fixe (ex. 2g).
# - THESES_BATCH_HEAP_DUMP_DIR : où écrire l'image du heap si la mémoire manque (/output, le volume
#   de sortie, par défaut).
# - THESES_BATCH_JAVA_OPTS : options JVM supplémentaires, séparées par des espaces.

HEAP_OPTION="-Xmx${THESES_BATCH_JAVA_XMX:-6g}"
THESES_BATCH_HEAP_DUMP_DIR="${THESES_BATCH_HEAP_DUMP_DIR:-/output}"

# "$@" pour surcharges Spring (ex. --app.anr.max-files=50).
# THESES_BATCH_JAVA_OPTS sans guillemets : chaque option doit devenir un argument distinct.
# shellcheck disable=SC2086
exec java "${HEAP_OPTION}" \
          -XX:+HeapDumpOnOutOfMemoryError \
          -XX:HeapDumpPath="${THESES_BATCH_HEAP_DUMP_DIR}" \
          -XX:+UseParallelGC \
          ${THESES_BATCH_JAVA_OPTS} \
          -Duser.timezone=Europe/Paris \
          -Duser.language=fr \
          -Dspring.profiles.active=dist \
          -jar /scripts/theses-batch.jar \
          "$@"

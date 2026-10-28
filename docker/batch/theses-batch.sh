#!/bin/bash

# ===================================================================
# Script principal d'exécution des batchs theses-batch
# Usage :
#   /scripts/theses-batch.sh anr [options...]
#   /scripts/theses-batch.sh datagouv [options...]
# ===================================================================

# Mémoire de la JVM, surchargeable par l'environnement du conteneur :
# - THESES_BATCH_JAVA_XMX : heap fixe (défaut : 6g).
# - THESES_BATCH_HEAP_DUMP_DIR : répertoire d'écriture du heap dump en cas d'OOM (défaut : /output).
# - THESES_BATCH_JAVA_OPTS : options JVM additionnelles.
HEAP_OPTION="-Xmx${THESES_BATCH_JAVA_XMX:-6g}"
THESES_BATCH_HEAP_DUMP_DIR="${THESES_BATCH_HEAP_DUMP_DIR:-/output}"

# Détection et activation conditionnelle de l'agent OpenTelemetry
OTEL_AGENT_OPT=""
if [ -f "/scripts/opentelemetry-javaagent.jar" ] && [ "${OTEL_ENABLED:-false}" = "true" ]; then
    OTEL_AGENT_OPT="-javaagent:/scripts/opentelemetry-javaagent.jar"
fi

# Détection du batch demandé (anr ou datagouv)
BATCH_TARGET=""
if [ "$1" = "anr" ] || [ "$1" = "datagouv" ] || [ "$1" = "dump" ]; then
    BATCH_TARGET="$1"
    shift
fi

# shellcheck disable=SC2086
exec java "${HEAP_OPTION}" \
          -XX:+HeapDumpOnOutOfMemoryError \
          -XX:HeapDumpPath="${THESES_BATCH_HEAP_DUMP_DIR}" \
          -XX:+UseParallelGC \
          ${OTEL_AGENT_OPT} \
          ${THESES_BATCH_JAVA_OPTS} \
          -Duser.timezone=Europe/Paris \
          -Duser.language=fr \
          -Dspring.profiles.active=dist \
          -jar /scripts/theses-batch.jar \
          ${BATCH_TARGET:+--batch.job=$BATCH_TARGET} \
          "$@"

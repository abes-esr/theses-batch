###
# Étape 1 : Compilation de theses-batch avec Maven et Eclipse Temurin 21
###
FROM maven:3-eclipse-temurin-21 AS build-image
WORKDIR /build/

# Configuration des locales fr_FR pour la phase de build
RUN apt update && DEBIAN_FRONTEND=noninteractive apt -y install locales \
    && sed -i '/fr_FR.UTF-8/s/^# //g' /etc/locale.gen \
    && locale-gen
ENV LANG=fr_FR.UTF-8
ENV LANGUAGE=fr_FR:fr
ENV LC_ALL=fr_FR.UTF-8

# On lance la compilation
# si on a un .m2 local on peut décommenter la ligne suivante pour
# éviter à maven de retélécharger toutes les dépendances
#COPY ./.m2/    /root/.m2/

# Copie du code source et compilation du JAR
COPY ./pom.xml /build/pom.xml
COPY ./src /build/src
RUN mvn --batch-mode \
    -Dmaven.test.skip=true \
    -Duser.timezone=Europe/Paris \
    -Duser.language=fr \
    package

###
# Étape 2 : Image d'exécution basée sur Rocky Linux 9 avec cronie (Politique Abes)
###
FROM rockylinux:9 AS batch-image
WORKDIR /scripts/

# Configuration des locales fr_FR
RUN dnf install -y langpacks-fr glibc-all-langpacks \
    && dnf clean all
ENV LANG=fr_FR.UTF-8
ENV LANGUAGE=fr_FR:fr
ENV LC_ALL=fr_FR.UTF-8

# Configuration du fuseau horaire Europe/Paris
ENV TZ=Europe/Paris
RUN dnf install -y tzdata \
    && ln -snf /usr/share/zoneinfo/$TZ /etc/localtime \
    && echo $TZ > /etc/timezone \
    && dnf clean all

# Installation des paquets système :
# - cronie : remplaçant de crond supportant le signal CTRL+C dans Docker pour un arrêt propre
# - procps : outils système de gestion des processus (ps, pgrep)
# - util-linux : flock, verrou empêchant deux batchs simultanés dans un conteneur (theses-batch.sh)
# - fontconfig & dejavu-sans-fonts : polices pour l'extraction PDF (ANR)
# - java-21-openjdk-headless : environnement d'exécution Java 21
RUN dnf install -y \
    cronie \
    procps \
    util-linux \
    fontconfig \
    dejavu-sans-fonts \
    java-21-openjdk-headless \
    && crond -V \
    && rm -rf /etc/cron.*/* \
    && dnf clean all

# Création des volumes et répertoires de données
RUN mkdir -p /data /output /input

# Téléchargement de l'agent OpenTelemetry (standard Abes pour le monitoring)
# Version figée et vérifiée par checksum (SHA-256 publié avec la release GitHub) :
# à mettre à jour ensemble lors d'une montée de version de l'agent.
ADD --checksum=sha256:e43a270df53c6f423e0ab157be9c27ccc01d5dc3232e162fe54777573db2f149 \
    https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.33.1/opentelemetry-javaagent.jar \
    /scripts/opentelemetry-javaagent.jar

# Scripts batch et JAR exécutable
COPY ./docker/batch/theses-batch.sh /scripts/theses-batch.sh
COPY ./docker/batch/theses-batch-anr.sh /scripts/theses-batch-anr.sh
COPY ./docker/batch/theses-batch-datagouv.sh /scripts/theses-batch-datagouv.sh
COPY --from=build-image /build/target/*.jar /scripts/theses-batch.jar
RUN chmod +x /scripts/*.sh

# Points d'entrée : un par conteneur. Aucun ENTRYPOINT ni CMD par défaut : chaque service
# du docker-compose (dépôt theses-batch-docker) doit choisir le sien avec "entrypoint".
COPY ./docker/batch/docker-entrypoint-anr.sh /docker-entrypoint-anr.sh
COPY ./docker/batch/docker-entrypoint-datagouv.sh /docker-entrypoint-datagouv.sh
RUN chmod +x /docker-entrypoint*.sh

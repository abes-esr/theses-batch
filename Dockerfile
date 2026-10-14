###
# Image pour la compilation de theses-batch
FROM maven:3-eclipse-temurin-21 AS build-image
WORKDIR /build/
# Installation et configuration de la locale FR
RUN apt update && DEBIAN_FRONTEND=noninteractive apt -y install locales
RUN sed -i '/fr_FR.UTF-8/s/^# //g' /etc/locale.gen && \
    locale-gen
ENV LANG=fr_FR.UTF-8
ENV LANGUAGE=fr_FR:fr
ENV LC_ALL=fr_FR.UTF-8
# On lance la compilation
# si on a un .m2 local on peut décommenter la ligne suivante pour
# éviter à maven de retélécharger toutes les dépendances
#COPY ./.m2/    /root/.m2/
COPY ./ /build/
RUN mvn --batch-mode \
    -Dmaven.test.skip=true \
    -Duser.timezone=Europe/Paris \
    -Duser.language=fr \
    package


###
# Image pour l'exécution du batch theses-batch
FROM eclipse-temurin:21-jre
WORKDIR /scripts/

# Configuration des locales fr_FR
RUN apt update && DEBIAN_FRONTEND=noninteractive apt -y install locales \
    && sed -i '/fr_FR.UTF-8/s/^# //g' /etc/locale.gen \
    && locale-gen \
    && apt clean \
    && rm -rf /var/lib/apt/lists/*
ENV LANG=fr_FR.UTF-8
ENV LANGUAGE=fr_FR:fr
ENV LC_ALL=fr_FR.UTF-8

# Copie du JAR et du script
COPY ./docker/batch/theses-batch.sh /scripts/theses-batch.sh
COPY --from=build-image /build/target/*.jar /scripts/theses-batch.jar
RUN chmod +x /scripts/*.sh

# Entrypoint
COPY ./docker/batch/docker-entrypoint.sh /docker-entrypoint.sh
RUN chmod +x /docker-entrypoint.sh

ENTRYPOINT ["/docker-entrypoint.sh"]
CMD ["/scripts/theses-batch.sh"]

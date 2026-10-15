#!/bin/bash

# "$@" pour surcharges Spring (ex. --app.anr.max-files=50).
exec java -Duser.timezone=Europe/Paris \
          -Duser.language=fr \
          -Dspring.profiles.active=dist \
          -jar /scripts/theses-batch.jar \
          "$@"

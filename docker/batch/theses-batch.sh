#!/bin/bash

exec java -jar /scripts/theses-batch.jar \
          -Duser.timezone=Europe/Paris \
          -Duser.language=fr

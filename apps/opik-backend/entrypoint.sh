#!/bin/bash

echo $(pwd)

jwebserver -d /opt/opik/redoc -b 0.0.0.0 -p 3003 &

echo "OPIK_VERSION=$OPIK_VERSION"


# Check if ENABLE_VIRTUAL_THREADS is set to true
if [ "$ENABLE_VIRTUAL_THREADS" = "true" ]; then
    JAVA_OPTS="$JAVA_OPTS -Dreactor.schedulers.defaultBoundedElasticOnVirtualThreads=true"
fi

java $JAVA_OPTS -jar opik-backend-$OPIK_VERSION.jar server config.yml

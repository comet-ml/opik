#!/bin/bash

echo $(pwd)

jwebserver -d /opt/opik/redoc -b 0.0.0.0 -p 3003 &

echo "OPIK_VERSION=$OPIK_VERSION"
echo "OTEL_SDK_DISABLED=$OTEL_SDK_DISABLED"
echo "OTEL_VERSION=$OTEL_VERSION"

if [[ "${OTEL_SDK_DISABLED}" == "false" ]];then
    curl -L -o /tmp/opentelemetry-javaagent.jar https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_VERSION}/opentelemetry-javaagent.jar
    JAVA_OPTS="$JAVA_OPTS -javaagent:/tmp/opentelemetry-javaagent.jar"
fi

# Check if ENABLE_VIRTUAL_THREADS is set to true
if [ "$ENABLE_VIRTUAL_THREADS" = "true" ]; then
    JAVA_OPTS="$JAVA_OPTS -Dreactor.schedulers.defaultBoundedElasticOnVirtualThreads=true"
fi

java $JAVA_OPTS -jar opik-backend-$OPIK_VERSION.jar server config.yml

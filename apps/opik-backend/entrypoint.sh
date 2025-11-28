#!/bin/bash
set -e

echo "Starting redoc server..."
jwebserver -d /opt/opik/redoc -b 0.0.0.0 -p 3003 &
echo "Redoc service successfully started as background process"

echo "Current directory is: $(pwd)"

echo "OPIK_VERSION=$OPIK_VERSION"
echo "OPIK_OTEL_SDK_ENABLED=$OPIK_OTEL_SDK_ENABLED"
echo "OTEL_VERSION=$OTEL_VERSION"

if [[ "${OPIK_OTEL_SDK_ENABLED}" == "true" && "${OTEL_VERSION}" != "" && "${OTEL_EXPORTER_OTLP_ENDPOINT}" != "" ]];then
    echo "Downloading Open Telemetry Java Agent"
    OTEL_SERVICE_NAME="${OTEL_SERVICE_NAME:-opik-backend}"
    # Only set OTEL_RESOURCE_ATTRIBUTES if not already provided
    if [ -z "$OTEL_RESOURCE_ATTRIBUTES" ]; then
        export OTEL_RESOURCE_ATTRIBUTES="service.name=${OTEL_SERVICE_NAME},service.version=${OPIK_VERSION}"
    fi
    OTEL_JAVAAGENT_DOWNLOAD_URL="${OTEL_JAVAAGENT_DOWNLOAD_URL:-https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_VERSION}/opentelemetry-javaagent.jar}"
    curl -L -o /tmp/opentelemetry-javaagent.jar "${OTEL_JAVAAGENT_DOWNLOAD_URL}"

    # Add Opik telemetry extension to the agent
    if [ -f "/opt/opik/opik-telemetry-extension.jar" ]; then
        echo "Adding Opik telemetry extension to OpenTelemetry agent"
        JAVA_OPTS="$JAVA_OPTS -javaagent:/tmp/opentelemetry-javaagent.jar"
        JAVA_OPTS="$JAVA_OPTS -Dotel.javaagent.extensions=/opt/opik/opik-telemetry-extension.jar"
    else
        echo "Opik telemetry extension not found, using standard OpenTelemetry agent"
        JAVA_OPTS="$JAVA_OPTS -javaagent:/tmp/opentelemetry-javaagent.jar"
    fi

    echo "Successfully configured Open Telemetry Java Agent with Opik extensions"
else
    echo "Skipping download of the Open Telemetry Java Agent"
fi

# Check if ENABLE_VIRTUAL_THREADS is set to true
if [ "$ENABLE_VIRTUAL_THREADS" = "true" ]; then
    echo "Enabling virtual threads"
    JAVA_OPTS="$JAVA_OPTS -Dreactor.schedulers.defaultBoundedElasticOnVirtualThreads=true"
fi

echo "Starting opik-backend service..."
java $JAVA_OPTS -jar opik-backend-$OPIK_VERSION.jar server config.yml

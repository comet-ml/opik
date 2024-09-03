#!/bin/bash

echo $(pwd)

jwebserver -d /opt/opik/redoc -b 0.0.0.0 -p 3003 &

echo "OPIK_VERSION=$OPIK_VERSION"
echo "NEW_RELIC_ENABLED=$NEW_RELIC_ENABLED"
echo "NEW_RELIC_VERSION=$NEW_RELIC_VERSION"

if [[ "${NEW_RELIC_ENABLED}" == "true" && "${NEW_RELIC_LICENSE_KEY}" != "" ]];then 
    curl -o /tmp/newrelic-agent.jar https://download.newrelic.com/newrelic/java-agent/newrelic-agent/${NEW_RELIC_VERSION}/newrelic-agent-${NEW_RELIC_VERSION}.jar
    JAVA_OPTS="$JAVA_OPTS -javaagent:/tmp/newrelic-agent.jar"
fi

# Check if ENABLE_VIRTUAL_THREADS is set to true
if [ "$ENABLE_VIRTUAL_THREADS" = "true" ]; then
    JAVA_OPTS="$JAVA_OPTS -Dreactor.schedulers.defaultBoundedElasticOnVirtualThreads=true"
fi

java $JAVA_OPTS -jar opik-backend-$OPIK_VERSION.jar server config.yml

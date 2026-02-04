#!/bin/bash

# Use explicit project name to avoid dependency on compose file location
# The project name "opik" is defined in docker-compose.yaml
PROJECT_NAME="opik"

max_retries=5
wait_interval=3
retries=0

while [[ $retries -lt $max_retries ]]
do
    containers=$(docker compose -p "$PROJECT_NAME" ps --status running -q 2>/dev/null)
    if [ -z "$containers" ]; then
        echo "Waiting for containers to be up... (Attempt: $((retries+1))/$max_retries)"
        sleep $wait_interval
        retries=$((retries+1))
    else
        echo "Containers running"
        docker compose -p "$PROJECT_NAME" ps
        break
    fi
done

if [[ $retries -eq $max_retries ]]; then
    echo "Containers failed to start"
    docker compose -p "$PROJECT_NAME" ps
    exit 1
fi

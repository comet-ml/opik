#!/bin/bash

cd deployment/docker-compose
max_retries=5
wait_interval=3
retries=0

while [[ $retries -lt $max_retries ]]
do
    containers=$(docker compose ps --status running -q)
    if [ -z "$containers" ]; then
        echo "Waiting for containers to be up... (Attempt: $((retries+1))/$max_retries)"
        sleep $wait_interval
        retries=$((retries+1))
    else
        echo "Containers running"
        docker compose ps
        break
    fi
done

if [[ $retries -eq $max_retries ]]; then
    echo "Containers failed to start"
    docker compose ps
    exit 1
fi

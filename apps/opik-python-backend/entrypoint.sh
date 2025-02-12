#!/bin/sh
# Using sh as bash is not available in the Alpine image
set -e

echo "Starting the Docker daemon as background process"
dockerd-entrypoint.sh &

SLEEP_SECONDS=1
MAX_ATTEMPTS=5

attempts=1

until docker info >/dev/null 2>&1 || [ $attempts -ge $MAX_ATTEMPTS ]; do
  echo "Waiting ${SLEEP_SECONDS}s for the Docker daemon to start, attempt: $attempts"
  sleep $SLEEP_SECONDS
  attempts=$((attempts+1))
done

if [ $attempts -ge $MAX_ATTEMPTS ]; then
  echo "Docker daemon did not start after $MAX_ATTEMPTS attempts"
  exit 1
fi

echo "Docker daemon started successfully after $attempts attempts"

echo "Loading the Opik Sandbox Executor Python image"
docker load < "./images/${PYTHON_CODE_EXECUTOR_ASSET_NAME}"
echo "Successfully loaded the Opik Sandbox Executor Python image"

echo "Starting the Opik Python Backend server"
gunicorn --workers 4 --bind=0.0.0.0:8000 --chdir ./src 'opik_backend:create_app()'

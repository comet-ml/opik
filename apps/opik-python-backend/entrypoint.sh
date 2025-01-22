#!/bin/sh
# Using sh as bash is not available in the Alpine image
set -e

echo "Starting the Docker daemon as background process"
dockerd-entrypoint.sh &

sleep_seconds=1
attempts=1
max_attempts=5

until docker info >/dev/null 2>&1 || [ $attempts -ge $max_attempts ]; do
  echo "Waiting ${sleep_seconds}s for the Docker daemon to start, attempt: $attempts"
  sleep $sleep_seconds
  attempts=$((attempts+1))
done

if [ $attempts -ge $max_attempts ]; then
  echo "Docker daemon did not start after $max_attempts attempts"
  exit 1
fi

echo "Docker daemon started successfully after $attempts attempts"

# Pre-pulling the to warm up the server the first time it attempts to run the image
echo "Pulling the Opik Sandbox Executor Python image"
docker pull "$PYTHON_CODE_EXECUTOR_IMAGE_REGISTRY"/"$PYTHON_CODE_EXECUTOR_IMAGE_NAME":"$PYTHON_CODE_EXECUTOR_IMAGE_TAG"
echo "Successfully pulled the Opik Sandbox Executor Python image"

echo "Starting the Opik Python Backend server"
gunicorn --workers 4 --bind=0.0.0.0:8000 --chdir ./src 'opik_backend:create_app()'

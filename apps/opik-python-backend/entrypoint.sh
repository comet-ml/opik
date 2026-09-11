#!/bin/sh
# Using sh as bash is not available in the Alpine image
set -e

if [ "$PYTHON_CODE_EXECUTOR_STRATEGY" = "docker" ]; then
  echo "Starting the Docker daemon as background process"
  dockerd-entrypoint.sh &

  SLEEP_SECONDS=1
  MAX_ATTEMPTS=30
  attempts=1

  until docker info >/dev/null 2>&1 || [ $attempts -ge $MAX_ATTEMPTS ]; do
    echo "Waiting ${SLEEP_SECONDS}s for the Docker daemon to start, attempt: $attempts, out of: $MAX_ATTEMPTS"
    sleep $SLEEP_SECONDS
    attempts=$((attempts+1))
  done

  if [ $attempts -ge $MAX_ATTEMPTS ]; then
    echo "Docker daemon did not start after $MAX_ATTEMPTS attempts"
    exit 1
  fi

  echo "Docker daemon started successfully after $attempts attempts"

  if [ -s "./images/${PYTHON_CODE_EXECUTOR_ASSET_NAME}.tar.gz" ]; then
    echo "Loading the Opik Sandbox Executor Python image"
    docker load < "./images/${PYTHON_CODE_EXECUTOR_ASSET_NAME}.tar.gz"
    echo "Successfully loaded the Opik Sandbox Executor Python image"
  else
    echo "Opik Sandbox Executor Python image not found"
  fi
else
  echo "[INFO] Skipping Docker daemon startup (PYTHON_CODE_EXECUTOR_STRATEGY=$PYTHON_CODE_EXECUTOR_STRATEGY)"
fi

echo "Starting the Opik Python Backend server"
# Use same number of threads as container pool size
NUM_THREADS=${PYTHON_CODE_EXECUTOR_PARALLEL_NUM:-5}

echo "OPIK_VERSION=$OPIK_VERSION"
echo "OPIK_OTEL_SDK_ENABLED=$OPIK_OTEL_SDK_ENABLED"

# Configure OpenTelemetry environment if enabled
if [ "$OPIK_OTEL_SDK_ENABLED" = "true" ]; then
  echo "Starting the Opik Python Backend server with Open Telemetry instrumentation"

  if [ -z "$OTEL_RESOURCE_ATTRIBUTES" ]; then
    export OTEL_RESOURCE_ATTRIBUTES="service.name=opik-python-backend,service.version=${OPIK_VERSION}"
  fi

  export OTEL_PYTHON_LOGGING_AUTO_INSTRUMENTATION_ENABLED=true
  export OTEL_PYTHON_LOG_CORRELATION=true

  INSTRUMENTATION_CMD="opentelemetry-instrument"
else
  echo "Starting the Opik Python Backend server without Open Telemetry instrumentation"
  INSTRUMENTATION_CMD=""
fi

# Configure port with environment variable
PYTHON_BACKEND_PORT=${PYTHON_BACKEND_PORT:-8000}
echo "PYTHON_BACKEND_PORT=$PYTHON_BACKEND_PORT"

# Single parameterized gunicorn command
$INSTRUMENTATION_CMD gunicorn --access-logfile '-' \
  --access-logformat '{"body_bytes_sent": %(B)s, "http_referer": "%(f)s", "http_user_agent": "%(a)s", "remote_addr": "%(h)s", "remote_user": "%(u)s", "request_length": 0, "request_time": %(L)s, "request": "%(r)s", "source": "gunicorn", "status": %(s)s, "time_local": "%(t)s", "time": %(T)s, "x_forwarded_for": "%(h)s"}' \
  --workers 1 \
  --threads "$NUM_THREADS" \
  --worker-class gthread \
  --bind=0.0.0.0:$PYTHON_BACKEND_PORT \
  --chdir ./src 'opik_backend:create_app()'

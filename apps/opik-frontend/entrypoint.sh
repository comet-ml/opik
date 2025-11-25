#!/bin/sh
set -eu

echo "🚀 Starting Opik Frontend..."

# Set default values for environment variables
export NGINX_PID="${NGINX_PID:-/run/nginx.pid}"
export OTEL_COLLECTOR_HOST="${OTEL_COLLECTOR_HOST:-otel-collector}"
# Nginx OpenTelemetry module uses gRPC (HTTP/2), so use port 4317, not 4318 (HTTP)
export OTEL_COLLECTOR_PORT="${OTEL_COLLECTOR_PORT:-4317}"
export FLUENT_BIT_HOST="${FLUENT_BIT_HOST:-fluent-bit}"
export FLUENT_BIT_PORT="${FLUENT_BIT_PORT:-5140}"
export NGINX_PORT="${NGINX_PORT:-8080}"
export OTEL_TRACE="${OTEL_TRACE:-off}"


# Process nginx.conf (not a template, so needs manual processing)
# Note: .template files in conf.d/ are handled by /docker-entrypoint.d/20-envsubst-on-templates.sh
echo "Processing nginx.conf..."
if [ -f /etc/nginx/nginx.conf ]; then
    TMP_FILE=$(mktemp)
    envsubst < /etc/nginx/nginx.conf > "$TMP_FILE" && cat "$TMP_FILE" > /etc/nginx/nginx.conf
    rm "$TMP_FILE"
fi

#!/bin/sh
set -eu

echo "🚀 Starting Opik Frontend..."

# Set default values for environment variables
export NGINX_PID="${NGINX_PID:-/run/nginx.pid}"
export OTEL_COLLECTOR_HOST="${OTEL_COLLECTOR_HOST:-otel-collector}"
export OTEL_COLLECTOR_PORT="${OTEL_COLLECTOR_PORT:-4317}"
export OTEL_TRACES_EXPORTER="${OTEL_TRACES_EXPORTER:-otlp}"
export OTEL_EXPORTER_OTLP_TRACES_ENDPOINT="${OTEL_EXPORTER_OTLP_TRACES_ENDPOINT:-http://${OTEL_COLLECTOR_HOST}:${OTEL_COLLECTOR_PORT}}"
export NGINX_PORT="${NGINX_PORT:-8080}"
export OTEL_TRACE="${OTEL_TRACE:-off}"
export NGINX_EXTRA_ACCESS_LOG="${NGINX_EXTRA_ACCESS_LOG:-}"
export NGINX_EXTRA_ERROR_LOG="${NGINX_EXTRA_ERROR_LOG:-}"
export NGINX_ACCESS_LOG_FORMAT="${NGINX_ACCESS_LOG_FORMAT:-main}"
export NGINX_ERROR_LOG_LEVEL="${NGINX_ERROR_LOG_LEVEL:-error}"

VARS='$NGINX_PID $NGINX_PORT $OTEL_TRACE $OTEL_COLLECTOR_HOST $OTEL_COLLECTOR_PORT $OTEL_TRACES_EXPORTER $OTEL_EXPORTER_OTLP_TRACES_ENDPOINT $NGINX_EXTRA_ACCESS_LOG $NGINX_EXTRA_ERROR_LOG $NGINX_ACCESS_LOG_FORMAT $NGINX_ERROR_LOG_LEVEL'
    
# Note: .template files in conf.d/ are handled by /docker-entrypoint.d/20-envsubst-on-templates.sh
# but we need to process them manually here to set the OTEL_TRACES_EXPORTER and other variables
for template in /etc/nginx/conf.d/*.conf; do
    [ -f "$template" ] || continue
    
    # Extract filename without path
    filename=$(basename "$template")
    
    echo "Processing $template -> /etc/nginx/conf.d/$filename"
    
    TMP_FILE=$(mktemp)
    envsubst "$VARS" < "$template" > "$TMP_FILE"
    # We output to conf.d directly
    cat "$TMP_FILE" > "/etc/nginx/conf.d/$filename"
    rm "$TMP_FILE"
done


# Process nginx.conf (not a template, so needs manual processing)
echo "Processing nginx.conf..."
if [ -f /etc/nginx/nginx.conf ]; then
    TMP_FILE=$(mktemp)
    envsubst "$VARS" < /etc/nginx/nginx.conf > "$TMP_FILE" && cat "$TMP_FILE" > /etc/nginx/nginx.conf
    rm "$TMP_FILE"
fi

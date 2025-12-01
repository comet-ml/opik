#!/bin/sh
set -eu

echo "🚀 Starting Opik Frontend..."

# Set default values for environment variables
export NGINX_PID="${NGINX_PID:-/run/nginx.pid}"
export OTEL_COLLECTOR_HOST="${OTEL_COLLECTOR_HOST:-jaeger}"
# Nginx OpenTelemetry module uses gRPC (HTTP/2), so use port 4317, not 4318 (HTTP)
export OTEL_COLLECTOR_PORT="${OTEL_COLLECTOR_PORT:-4317}"
export NGINX_PORT="${NGINX_PORT:-8080}"
export OTEL_TRACE="${OTEL_TRACE:-off}"


VARS='$NGINX_PID $NGINX_PORT $OTEL_TRACE $OTEL_COLLECTOR_HOST $OTEL_COLLECTOR_PORT'
    
echo "patch configs already updated on 20-envsubst-on-templates.sh..."
for template in /etc/nginx/conf.d/*.conf; do
    [ -f "$template" ] || continue
    
    # Extract filename without path
    filename=$(basename "$template")
    
    echo "Processing $template -> /etc/nginx/conf.d/$filename"
    
    TMP_FILE=$(mktemp)
    # We only want to substitute OTEL_TRACE_ID_JSON, leaving other variables alone
    envsubst "$VARS" < "$template" > "$TMP_FILE"
    # We output to conf.d directly
    cat "$TMP_FILE" > "/etc/nginx/conf.d/$filename"
    rm "$TMP_FILE"
done


# Process nginx.conf (not a template, so needs manual processing)
# Note: .template files in conf.d/ are handled by /docker-entrypoint.d/20-envsubst-on-templates.sh
echo "Processing nginx.conf..."
if [ -f /etc/nginx/nginx.conf ]; then
    TMP_FILE=$(mktemp)
    envsubst "$VARS" < /etc/nginx/nginx.conf > "$TMP_FILE" && cat "$TMP_FILE" > /etc/nginx/nginx.conf
    rm "$TMP_FILE"
fi

# Start nginx

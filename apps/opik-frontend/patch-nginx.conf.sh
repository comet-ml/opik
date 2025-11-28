#!/bin/sh
set -eu

echo "🚀 Starting Opik Frontend..."

# Set default values for environment variables
export NGINX_PID="${NGINX_PID:-/run/nginx.pid}"
export OTEL_COLLECTOR_HOST="${OTEL_COLLECTOR_HOST:-otel-collector}"
# Nginx OpenTelemetry module uses gRPC (HTTP/2), so use port 4317, not 4318 (HTTP)
export OTEL_COLLECTOR_PORT="${OTEL_COLLECTOR_PORT:-4317}"
export NGINX_PORT="${NGINX_PORT:-8080}"
export OTEL_TRACE="${OTEL_TRACE:-off}"
# Default SYSLOG_SERVER_HOST to OTEL_COLLECTOR_HOST if not set explicitly, otherwise default to otel-collector
export SYSLOG_SERVER_HOST="${SYSLOG_SERVER_HOST:-${OTEL_COLLECTOR_HOST}}"
export SYSLOG_SERVER_PORT="${SYSLOG_SERVER_PORT:-5140}"

# Set OpenTelemetry module load directive based on OTEL_TRACE
if [ "${OTEL_TRACE}" = "on" ]; then
    export OTEL_COMMENT_OUT=""
    # Enable syslog logging if OTEL_TRACE is on
    export NGINX_ACCESS_LOG_SYSLOG="access_log  syslog:server=${SYSLOG_SERVER_HOST}:${SYSLOG_SERVER_PORT},facility=local7 logger-json;"
    
    # Prepare variable for json-log template substitution
    export OTEL_TRACE_ID_JSON=', "otel_trace_id": "$otel_trace_id"'
else
    export OTEL_COMMENT_OUT="# (OTEL_TRACE=off)"
    # Disable syslog logging if OTEL_TRACE is off
    export NGINX_ACCESS_LOG_SYSLOG="# access_log syslog disabled (OTEL_TRACE=off)"
    
    export OTEL_TRACE_ID_JSON=""
fi

VARS='$NGINX_PID $NGINX_PORT $OTEL_TRACE $OTEL_COLLECTOR_HOST $OTEL_TRACE_ID_JSON $OTEL_COLLECTOR_PORT $OTEL_COMMENT_OUT $SYSLOG_SERVER_HOST $SYSLOG_SERVER_PORT $NGINX_ACCESS_LOG_SYSLOG'
    
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

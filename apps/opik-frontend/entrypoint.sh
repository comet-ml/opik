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
    export OTEL_MODULE_LOAD="load_module modules/ngx_otel_module.so;"
    # Enable syslog logging if OTEL_TRACE is on
    export NGINX_ACCESS_LOG_SYSLOG="access_log  syslog:server=${SYSLOG_SERVER_HOST}:${SYSLOG_SERVER_PORT},facility=local7 main;"
else
    export OTEL_MODULE_LOAD="# load_module modules/ngx_otel_module.so;"
    # Disable syslog logging if OTEL_TRACE is off
    export NGINX_ACCESS_LOG_SYSLOG="# access_log syslog disabled (OTEL_TRACE=off)"
fi


# Process nginx.conf (not a template, so needs manual processing)
# Note: .template files in conf.d/ are handled by /docker-entrypoint.d/20-envsubst-on-templates.sh
echo "Processing nginx.conf..."
if [ -f /etc/nginx/nginx.conf ]; then
    TMP_FILE=$(mktemp)
    # Only substitute specific variables to avoid clobbering Nginx variables like $remote_addr
    VARS='$NGINX_PID $OTEL_TRACE $OTEL_COLLECTOR_HOST $OTEL_COLLECTOR_PORT $OTEL_MODULE_LOAD $SYSLOG_SERVER_HOST $SYSLOG_SERVER_PORT $NGINX_ACCESS_LOG_SYSLOG'
    envsubst "$VARS" < /etc/nginx/nginx.conf > "$TMP_FILE" && cat "$TMP_FILE" > /etc/nginx/nginx.conf
    rm "$TMP_FILE"
fi

# Start nginx

#!/bin/sh
set -e

echo "🚀 Starting Opik Frontend..."

# Function to handle graceful shutdown
cleanup() {
    echo "Shutting down nginx..."
    if [ ! -z "$NGINX_PID" ]; then
        kill $NGINX_PID 2>/dev/null || true
    fi
    if [ ! -z "$TAIL_ERROR_PID" ]; then
        kill $TAIL_ERROR_PID 2>/dev/null || true
    fi
    wait
    exit 0
}

# Set up signal handlers
trap cleanup SIGTERM SIGINT

# Configure OpenTelemetry based on OTEL_ENABLE environment variable
echo "Configuring OpenTelemetry..."
if [ "${OTEL_ENABLE:-false}" = "true" ]; then
    echo "✅ OpenTelemetry tracing enabled"
    export OTEL_TRACE_STATE="on"
else
    echo "ℹ️  OpenTelemetry tracing disabled"
    export OTEL_TRACE_STATE="off"
fi

# Set default values for OTEL collector host and port
export OTEL_COLLECTOR_HOST="${OTEL_COLLECTOR_HOST:-otel-collector}"
export OTEL_COLLECTOR_PORT="${OTEL_COLLECTOR_PORT:-4318}"

# Set default value for nginx listen port (non-privileged port)
export NGINX_PORT="${NGINX_PORT:-8080}"

# Process all .conf files (nginx.conf and conf.d/*.conf) with envsubst
echo "Processing nginx configuration files..."
for conf in /etc/nginx/nginx.conf /etc/nginx/conf.d/*.conf; do
    if [ -f "$conf" ]; then
        echo "  Processing $(basename $conf)"
        TMP_FILE=$(mktemp)
        envsubst < "$conf" > $TMP_FILE && mv $TMP_FILE "$conf"
    fi
done

# Ensure log files exist
touch /var/log/nginx/access.log /var/log/nginx/error.log

# Start tailing error log to stderr for kubectl logs
# Note: access_log is configured to write to both file and stdout in nginx.conf
# error_log only supports one destination, so we tail the file to stderr
# tail -f /var/log/nginx/error.log >&2 &
# TAIL_ERROR_PID=$!

# Start nginx
echo "Starting nginx..."
nginx -g "daemon off;" &
NGINX_PID=$!

echo "✅ Frontend ready!"

# Wait for nginx
wait

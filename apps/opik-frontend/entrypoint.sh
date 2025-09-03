#!/bin/bash
set -e

echo "🚀 Starting Opik Frontend with optional OTEL log shipping..."

# Function to handle graceful shutdown
cleanup() {
    echo "Shutting down services..."
    if [ ! -z "$NGINX_PID" ]; then
        echo "Stopping nginx..."
        kill $NGINX_PID 2>/dev/null || true
    fi
    if [ ! -z "$FLUENT_BIT_PID" ]; then
        echo "Stopping Fluent Bit..."
        kill $FLUENT_BIT_PID 2>/dev/null || true
    fi
    wait
    exit 0
}

# Set up signal handlers
trap cleanup SIGTERM SIGINT

# Start nginx in background
echo "Starting nginx..."
nginx -g "daemon off;" &
NGINX_PID=$!

# Start Fluent Bit if OTEL log export is enabled
if [ "${ENABLE_OTEL_LOG_EXPORT:-false}" = "true" ]; then
    echo "📡 OTEL log export enabled - starting Fluent Bit..."
    
    # Set collector endpoint from HOST/PORT
    echo "  📊 Collector endpoint: ${OTEL_COLLECTOR_HOST}:${OTEL_COLLECTOR_PORT}"
    

    if [ -x "/opt/fluent-bit/bin/fluent-bit" ] && [ -f "/etc/fluent-bit/fluent-bit.conf" ]; then
        /opt/fluent-bit/bin/fluent-bit --config=/etc/fluent-bit/fluent-bit.conf &
        FLUENT_BIT_PID=$!
        echo "Fluent Bit started with PID $FLUENT_BIT_PID"
    else
        echo "[ERROR] Fluent Bit not started — Fluent Bit is not installed"
    fi


else
    echo "📝 OTEL log export disabled - nginx logs locally only"
fi

echo "✅ Frontend ready!"

# Wait for processes
wait

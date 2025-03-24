#!/bin/sh
set -e

# Set device
export OPIK_GUARDRAILS_DEVICE=${OPIK_GUARDRAILS_DEVICE:-cuda:0}

echo "Starting the Opik Guardrails Backend server"
gunicorn --workers=1 --threads=4 --bind=0.0.0.0:5000 'opik_guardrails:create_app()'

#!/bin/sh
set -e

echo "Starting the Opik Guardrails Backend server"
gunicorn --workers=1 --threads=4 --bind=0.0.0.0:5000 --chdir ./src 'opik_guardrails:create_app()'

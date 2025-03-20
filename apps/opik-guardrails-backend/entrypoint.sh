#!/bin/sh
set -e

echo "Downloading spaCy model for PII detection"
python -m spacy download en_core_web_lg

echo "Starting the Opik Guardrails Backend server"
gunicorn --workers=1 --threads=4 --bind=0.0.0.0:5000 --chdir ./src 'opik_guardrails:create_app()'

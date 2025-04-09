#!/bin/sh
set -e

# Set device
export OPIK_GUARDRAILS_DEVICE=${OPIK_GUARDRAILS_DEVICE:-cuda:0}

echo "Starting the Opik Guardrails Backend server"
gunicorn --workers=1 --threads=4 --bind=0.0.0.0:5000 --access-logfile=- \
    --access-logformat '{"body_bytes_sent": %(B)s, "http_referer": "%(f)s", "http_user_agent": "%(a)s", "remote_addr": "%(h)s", "remote_user": "%(u)s", "request_length": 0, "request_time": %(L)s, "request": "%(r)s", "source": "gunicorn", "status": %(s)s, "time_local": "%(t)s", "time": %(T)s, "x_forwarded_for": "%(h)s"}' \
    --log-level=info 'opik_guardrails:create_app()'

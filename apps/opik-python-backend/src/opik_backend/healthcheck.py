# opik_backend/healthcheck.py
from flask import Blueprint
import os
import redis

healthcheck = Blueprint("healthcheck", __name__)

@healthcheck.route("/healthcheck", methods=["GET"])
def health():
    return "OK", 200


@healthcheck.route("/health/liveness", methods=["GET"])
def liveness():
    # Process is up
    return "ALIVE", 200


@healthcheck.route("/health/readiness", methods=["GET"])
def readiness():
    # Check Redis connectivity quickly
    host = os.getenv("REDIS_HOST", "localhost")
    port = int(os.getenv("REDIS_PORT", "6379"))
    db = int(os.getenv("REDIS_DB", "0"))
    password = os.getenv("REDIS_PASSWORD")
    timeout = float(os.getenv("REDIS_TIMEOUT_SECONDS", "5"))

    try:
        client = redis.Redis(
            host=host,
            port=port,
            db=db,
            password=password if password else None,
            decode_responses=True,
            socket_timeout=timeout,
            socket_connect_timeout=timeout,
            socket_keepalive=True,
        )
        client.ping()
        return "READY", 200
    except Exception:
        return "NOT_READY", 503

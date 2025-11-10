# opik_backend/healthcheck.py
from flask import Blueprint
from opik_backend.utils.redis_utils import get_redis_client
from opik_backend.utils.env_utils import is_rq_worker_enabled

healthcheck = Blueprint("healthcheck", __name__)

rq_enabled = is_rq_worker_enabled()

@healthcheck.route("/healthcheck", methods=["GET"])
def health():
    return "OK", 200


@healthcheck.route("/health/liveness", methods=["GET"])
def liveness():
    # Process is up
    return "ALIVE", 200


@healthcheck.route("/health/readiness", methods=["GET"])
def readiness():
    
    if not rq_enabled:
        return "READY", 200

    # Check Redis connectivity quickly
    try:
        client = get_redis_client()
        client.ping()
        return "READY", 200
    except Exception:
        return "NOT_READY", 503

# opik_backend/healthcheck.py
from flask import Blueprint

healthcheck = Blueprint("healthcheck", __name__)

@healthcheck.route("/healthcheck", methods=["GET"])
def health():
    return "OK", 200

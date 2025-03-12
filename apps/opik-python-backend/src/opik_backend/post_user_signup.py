from typing import Any, Dict
import os

from flask import request, abort, jsonify, Blueprint
from werkzeug.exceptions import HTTPException

from opik_backend.demo_data_generator import create_demo_data

OPIK_BACKEND_URL = os.getenv("OPIK_BACKEND_URL", "http://localhost:5173/api")

postUserSignup = Blueprint('post_user_signup', __name__, url_prefix='/v1/internal/post_user_signup')

@postUserSignup.errorhandler(400)
def bad_request(exception: HTTPException):
    return jsonify(error=str(exception)), 400


@postUserSignup.errorhandler(500)
def internal_server_error(exception: HTTPException):
    return jsonify(error=str(exception)), 500


@postUserSignup.route(rule="", methods=["POST"])
def execute():
    if request.method != "POST":
        return

    payload: Any = request.get_json(force=True)

    code: str = payload.get("workspace_name")
    if code is None:
        abort(400, "Field 'workspace_name' is missing in the request")

    apiKey: str = payload.get("comet_api_key")
    if apiKey is None:
        abort(400, "Field 'comet_api_key' is missing in the request")

    create_demo_data(OPIK_BACKEND_URL, code, apiKey)

    return jsonify({"message": "Demo data created"}), 200


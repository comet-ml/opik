from typing import Any, Dict
import os

from flask import request, abort, jsonify, Blueprint
from werkzeug.exceptions import HTTPException

from opik_backend.demo_data_generator import create_demo_data
from opik_backend.http_utils import build_error_response

OPIK_REVERSE_PROXY_URL = os.getenv("OPIK_REVERSE_PROXY_URL", "http://localhost:5173/api")

post_user_signup = Blueprint('post_user_signup', __name__, url_prefix='/v1/private/post_user_signup')

@post_user_signup.errorhandler(400)
def bad_request(exception: HTTPException):
    return build_error_response(exception, 400)


@post_user_signup.errorhandler(500)
def internal_server_error(exception: HTTPException):
    return build_error_response(exception, 500)


@post_user_signup.route(rule="", methods=["POST"])
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

    create_demo_data(OPIK_REVERSE_PROXY_URL, code, apiKey)

    return jsonify({"message": "Demo data created"}), 200


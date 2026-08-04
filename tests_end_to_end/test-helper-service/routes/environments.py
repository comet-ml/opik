"""Environment-related endpoints for test helper service"""

from flask import Blueprint, request, abort
from werkzeug.exceptions import HTTPException
from .utils import (
    get_opik_api_client,
    build_error_response,
    success_response,
    validate_required_fields,
)

environments_bp = Blueprint("environments", __name__)


@environments_bp.errorhandler(400)
def bad_request(exception: HTTPException):
    return build_error_response(exception, 400)


@environments_bp.errorhandler(404)
def not_found(exception: HTTPException):
    return build_error_response(exception, 404)


@environments_bp.errorhandler(500)
def internal_server_error(exception: HTTPException):
    return build_error_response(exception, 500)


@environments_bp.route("/create-environment", methods=["POST"])
def create_environment():
    data = request.get_json()
    validate_required_fields(data, ["name"])

    name = data["name"]
    client = get_opik_api_client()

    kwargs = {}
    if data.get("description"):
        kwargs["description"] = data["description"]
    if data.get("color"):
        kwargs["color"] = data["color"]

    client.environments.create_environment(name=name, **kwargs)

    environments = client.environments.find_environments()
    matches = [env for env in (environments.content or []) if env.name == name]
    if matches:
        created = matches[0]
        return success_response({"id": created.id, "name": created.name})

    abort(500, "Failed to retrieve created environment")


@environments_bp.route("/find-environments", methods=["GET"])
def find_environments():
    client = get_opik_api_client()
    environments = client.environments.find_environments()
    return success_response(
        {
            "environments": [
                {"id": env.id, "name": env.name}
                for env in (environments.content or [])
            ]
        }
    )


@environments_bp.route("/delete-environment", methods=["DELETE"])
def delete_environment():
    data = request.get_json()
    validate_required_fields(data, ["id"])

    environment_id = data["id"]
    client = get_opik_api_client()

    client.environments.delete_environments_batch(ids=[environment_id])

    return success_response({"id": environment_id})

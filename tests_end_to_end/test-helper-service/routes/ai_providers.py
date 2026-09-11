"""AI provider (LLM provider API key) endpoints for test helper service"""

from flask import Blueprint, request, abort
from werkzeug.exceptions import HTTPException
from .utils import (
    get_opik_api_client,
    build_error_response,
    success_response,
    validate_required_fields,
)

ai_providers_bp = Blueprint("ai_providers", __name__)


@ai_providers_bp.errorhandler(400)
def bad_request(exception: HTTPException):
    return build_error_response(exception, 400)


@ai_providers_bp.errorhandler(404)
def not_found(exception: HTTPException):
    return build_error_response(exception, 404)


@ai_providers_bp.errorhandler(500)
def internal_server_error(exception: HTTPException):
    return build_error_response(exception, 500)


@ai_providers_bp.route("/create-provider-api-key", methods=["POST"])
def create_provider_api_key():
    data = request.get_json()
    validate_required_fields(data, ["provider", "api_key"])

    provider = data["provider"]
    client = get_opik_api_client()

    kwargs = {}
    if data.get("name"):
        kwargs["name"] = data["name"]
    if data.get("provider_name"):
        kwargs["provider_name"] = data["provider_name"]

    client.llm_provider_key.store_llm_provider_api_key(
        provider=provider, api_key=data["api_key"], **kwargs
    )

    keys = client.llm_provider_key.find_llm_provider_keys()
    matches = [key for key in (keys.content or []) if key.provider == provider]
    if matches:
        created = matches[0]
        return success_response({"id": created.id, "provider": created.provider})

    abort(500, "Failed to retrieve created provider API key")


@ai_providers_bp.route("/find-provider-api-keys", methods=["GET"])
def find_provider_api_keys():
    client = get_opik_api_client()
    keys = client.llm_provider_key.find_llm_provider_keys()
    return success_response(
        {
            "providers": [
                {"id": key.id, "provider": key.provider}
                for key in (keys.content or [])
            ]
        }
    )


@ai_providers_bp.route("/delete-provider-api-key", methods=["DELETE"])
def delete_provider_api_key():
    data = request.get_json()
    validate_required_fields(data, ["id"])

    key_id = data["id"]
    client = get_opik_api_client()

    client.llm_provider_key.delete_llm_provider_api_keys_batch(ids=[key_id])

    return success_response({"id": key_id})

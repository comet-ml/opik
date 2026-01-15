"""Prompt-related endpoints for test helper service"""

from flask import Blueprint, request, abort
from werkzeug.exceptions import HTTPException
import time
from .utils import (
    get_opik_client,
    build_error_response,
    success_response,
    validate_required_fields,
)

prompts_bp = Blueprint("prompts", __name__)


@prompts_bp.errorhandler(400)
def bad_request(exception: HTTPException):
    return build_error_response(exception, 400)


@prompts_bp.errorhandler(404)
def not_found(exception: HTTPException):
    return build_error_response(exception, 404)


@prompts_bp.errorhandler(500)
def internal_server_error(exception: HTTPException):
    return build_error_response(exception, 500)


@prompts_bp.route("/create-prompt", methods=["POST"])
def create_prompt():
    data = request.get_json()
    validate_required_fields(data, ["name", "prompt"])

    name = data["name"]
    prompt_text = data["prompt"]

    client = get_opik_client()
    prompt = client.create_prompt(name=name, prompt=prompt_text)

    return success_response(
        {
            "name": prompt.name,
            "prompt": prompt.prompt,
            "commit": prompt.commit,
        }
    )


@prompts_bp.route("/get-prompt", methods=["GET"])
def get_prompt():
    name = request.args.get("name")
    if not name:
        abort(400, "Missing required parameter: name")

    commit = request.args.get("commit")
    client = get_opik_client()

    retries = 0
    max_retries = 5

    while retries < max_retries:
        if commit:
            prompt = client.get_prompt(name=name, commit=commit)
        else:
            prompt = client.get_prompt(name=name)

        if prompt:
            return success_response(
                {
                    "name": prompt.name,
                    "prompt": prompt.prompt,
                    "commit": prompt.commit,
                }
            )

        time.sleep(1)
        retries += 1

    abort(404, f"Prompt not found: {name}")


@prompts_bp.route("/update-prompt", methods=["POST"])
def update_prompt():
    data = request.get_json()
    validate_required_fields(data, ["name", "prompt"])

    name = data["name"]
    prompt_text = data["prompt"]

    client = get_opik_client()
    prompt = client.create_prompt(name=name, prompt=prompt_text)

    return success_response(
        {
            "name": prompt.name,
            "prompt": prompt.prompt,
            "commit": prompt.commit,
        }
    )


@prompts_bp.route("/delete-prompt", methods=["DELETE"])
def delete_prompt():
    data = request.get_json()
    validate_required_fields(data, ["name"])

    name = data["name"]
    client = get_opik_client()

    # Use the rest_client from the Opik client which is already properly configured
    prompts_response = client.rest_client.prompts.get_prompts(name=name)

    if not prompts_response.content or len(prompts_response.content) == 0:
        abort(404, f"Prompt not found: {name}")

    prompt = prompts_response.content[0]
    client.rest_client.prompts.delete_prompt(id=prompt.id)

    return success_response({"name": name})

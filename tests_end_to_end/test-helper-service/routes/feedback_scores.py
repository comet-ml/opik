"""Feedback scores/definitions-related endpoints for test helper service"""

from flask import Blueprint, request, abort
from werkzeug.exceptions import HTTPException
from opik.rest_api.types import (
    FeedbackCreate_Categorical,
    FeedbackCreate_Numerical,
    CategoricalFeedbackDetailCreate,
    NumericalFeedbackDetailCreate,
)
from .utils import (
    get_opik_api_client,
    build_error_response,
    success_response,
    validate_required_fields,
)

feedback_scores_bp = Blueprint("feedback_scores", __name__)


@feedback_scores_bp.errorhandler(400)
def bad_request(exception: HTTPException):
    return build_error_response(exception, 400)


@feedback_scores_bp.errorhandler(500)
def internal_server_error(exception: HTTPException):
    return build_error_response(exception, 500)


@feedback_scores_bp.route("/create-feedback-definition", methods=["POST"])
def create_feedback_definition():
    data = request.get_json()
    validate_required_fields(data, ["name", "type"])

    name = data["name"]
    definition_type = data["type"]
    client = get_opik_api_client()

    if definition_type == "categorical":
        categories = data.get("categories", {"a": 1.0, "b": 2.0})
        feedback_request = FeedbackCreate_Categorical(
            name=name,
            details=CategoricalFeedbackDetailCreate(categories=categories),
        )
    elif definition_type == "numerical":
        min_value = float(data.get("min", 0))
        max_value = float(data.get("max", 1))
        feedback_request = FeedbackCreate_Numerical(
            name=name,
            details=NumericalFeedbackDetailCreate(min=min_value, max=max_value),
        )
    else:
        abort(400, f"Invalid feedback definition type: {definition_type}")

    client.feedback_definitions.create_feedback_definition(request=feedback_request)

    definitions = client.feedback_definitions.find_feedback_definitions(name=name)
    if definitions and definitions.content:
        created_definition = definitions.content[0]
        return success_response(
            {
                "id": created_definition.id,
                "name": created_definition.name,
                "type": definition_type,
            }
        )

    abort(500, "Failed to retrieve created feedback definition")


@feedback_scores_bp.route("/get-feedback-definition", methods=["GET"])
def get_feedback_definition():
    name = request.args.get("name")
    if not name:
        abort(400, "Missing required parameter: name")
    client = get_opik_api_client()

    definitions = client.feedback_definitions.find_feedback_definitions(name=name)

    if not definitions or not definitions.content:
        abort(404, f"Feedback definition not found: {name}")

    definition = definitions.content[0]
    return success_response(
        {
            "id": definition.id,
            "name": definition.name,
            "type": definition.type,
            "details": definition.details,
        }
    )


@feedback_scores_bp.route("/update-feedback-definition", methods=["POST"])
def update_feedback_definition():
    data = request.get_json()
    validate_required_fields(data, ["id", "name"])

    definition_id = data["id"]
    name = data["name"]
    definition_type = data.get("type")
    client = get_opik_api_client()

    if definition_type == "categorical" and "categories" in data:
        categories = data["categories"]
        feedback_request = FeedbackCreate_Categorical(
            name=name,
            details=CategoricalFeedbackDetailCreate(categories=categories),
        )
    elif definition_type == "numerical":
        min_value = float(data.get("min", 0))
        max_value = float(data.get("max", 1))
        feedback_request = FeedbackCreate_Numerical(
            name=name,
            details=NumericalFeedbackDetailCreate(min=min_value, max=max_value),
        )
    else:
        abort(400, f"Invalid or missing feedback definition type: {definition_type}")

    client.feedback_definitions.update_feedback_definition(
        id=definition_id, request=feedback_request
    )

    return success_response({"id": definition_id, "name": name})


@feedback_scores_bp.route("/delete-feedback-definition", methods=["DELETE"])
def delete_feedback_definition():
    data = request.get_json()
    validate_required_fields(data, ["id"])

    definition_id = data["id"]
    client = get_opik_api_client()

    client.feedback_definitions.delete_feedback_definition_by_id(id=definition_id)

    return success_response({"id": definition_id})

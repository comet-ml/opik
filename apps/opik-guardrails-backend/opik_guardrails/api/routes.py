import logging

import flask
import pydantic

from opik_guardrails import schemas
from opik_guardrails.services import validation_engine

LOGGER = logging.getLogger(__name__)

guardrails_blueprint = flask.Blueprint(
    "guardrails", __name__, url_prefix="/api/v1/guardrails"
)


@guardrails_blueprint.errorhandler(pydantic.ValidationError)
def handle_validation_error(
    error: pydantic.ValidationError,
) -> tuple[flask.Response, int]:
    LOGGER.warning(f"Validation error: {error.errors()}")
    return flask.jsonify({"error": "Invalid input", "details": error.errors()}), 400


@guardrails_blueprint.errorhandler(Exception)
def handle_generic_exception(error: Exception) -> tuple[flask.Response, int]:
    LOGGER.error(f"Validation failed: {str(error)}", exc_info=True)
    return flask.jsonify({"error": f"Validation failed: {str(error)}"}), 500


@guardrails_blueprint.route("/validations", methods=["POST"])
def validate_combined() -> tuple[flask.Response, int]:
    if flask.request.method != "POST":
        return flask.jsonify({"error": "Method not allowed"}), 405

    data = flask.request.get_json()
    request_with_validated_text_and_type = schemas.GuardrailsValidationRequest(**data)

    all_validations_passed = True
    validation_results = []

    for validation_descriptor in request_with_validated_text_and_type.validations:
        validated_config = validation_engine.build_validation_config_from_raw_dict(
            validation_type=validation_descriptor.type,
            config_dict=validation_descriptor.config,
        )
        validation_result = validation_engine.run_validator(
            validation_type=validation_descriptor.type,
            text=request_with_validated_text_and_type.text,
            config=validated_config,
        )

        validation_results.append(validation_result.model_dump(serialize_as_any=True))

        if not validation_result.validation_passed:
            all_validations_passed = False

    return flask.jsonify(
        {
            "validation_passed": all_validations_passed,
            "validations": validation_results,
        }
    ), 200


healthcheck = flask.Blueprint("healthcheck", __name__)

@healthcheck.route("/healthcheck", methods=["GET"])
def health():
    return "OK", 200

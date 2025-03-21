import logging

import flask
import pydantic

from opik_guardrails import schemas
from opik_guardrails.services import validation_engine

LOGGER = logging.getLogger(__name__)

guardrails_blueprint = flask.Blueprint("api", __name__, url_prefix="/api")


@guardrails_blueprint.route("/validate-topic", methods=["POST"])
def validate_topic() -> flask.Response:
    try:
        data = flask.request.get_json()
        validated_request = schemas.RestrictedTopicValidationRequest(**data)

        validation_result = validation_engine.run_validator(
            schemas.ValidationType.RESTRICTED_TOPIC,
            validated_request.text,
            validated_request.config,
        )

        response = validation_result.model_dump()

        return flask.jsonify(response), 200

    except pydantic.ValidationError as validation_error:
        LOGGER.warning(f"Validation error: {validation_error.errors()}")
        return flask.jsonify(
            {"error": "Invalid input", "details": validation_error.errors()}
        ), 400

    except Exception as exception:
        LOGGER.error(f"Classification failed: {str(exception)}", exc_info=True)
        flask.abort(500, description=f"Classification failed: {str(exception)}")


@guardrails_blueprint.route("/validate-pii", methods=["POST"])
def validate_pii() -> flask.Response:
    try:
        data = flask.request.get_json()
        validated_request = schemas.PIIValidationRequest(**data)

        validation_result = validation_engine.run_validator(
            schemas.ValidationType.PII,
            validated_request.text,
            validated_request.config,
        )

        response = validation_result.model_dump()

        return flask.jsonify(response), 200

    except pydantic.ValidationError as validation_error:
        LOGGER.warning(f"Validation error: {validation_error.errors()}")
        return flask.jsonify(
            {"error": "Invalid input", "details": validation_error.errors()}
        ), 400

    except Exception as exception:
        LOGGER.error(f"PII detection failed: {str(exception)}", exc_info=True)
        flask.abort(500, description=f"PII detection failed: {str(exception)}")


@guardrails_blueprint.route("/validate", methods=["POST"])
def validate_combined() -> flask.Response:
    try:
        data = flask.request.get_json()
        validated_request = schemas.GuardrailsValidationRequest(**data)

        all_validations_passed = True
        validation_results = []

        for validation_descriptor in validated_request.validations:
            validation_result = validation_engine.run_validator(
                validation_type=validation_descriptor.type,
                text=validated_request.text,
                config=validation_descriptor.config,
            )

            validation_results.append(validation_result)

            if not validation_result.validation_passed:
                all_validations_passed = False

        return flask.jsonify(
            {
                "validation_passed": all_validations_passed,
                "validations": validation_results,
            }
        ), 200

    except pydantic.ValidationError as validation_error:
        LOGGER.warning(f"Validation error: {validation_error.errors()}")
        return flask.jsonify(
            {"error": "Invalid input", "details": validation_error.errors()}
        ), 400

    except Exception as exception:
        LOGGER.error(f"Validation failed: {str(exception)}", exc_info=True)
        flask.abort(500, description=f"Validation failed: {str(exception)}")

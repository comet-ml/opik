import logging

import flask
import pydantic

from opik_guardrails import schemas
from opik_guardrails.services import validation_engine
from opik_guardrails.services.validators import pii as pii_validator
from opik_guardrails.services.validators import (
    restricted_topic as restricted_topic_validator,
)

LOGGER = logging.getLogger(__name__)

guardrails_blueprint = flask.Blueprint("api", __name__, url_prefix="/api")

topics_relevance_classifier = restricted_topic_validator.RestrictedTopicValidator()
pii_detector = pii_validator.PIIValidator()


@guardrails_blueprint.route("/validate-topic", methods=["POST"])
def validate_topic() -> flask.Response:
    try:
        data = flask.request.get_json()
        validated_request = schemas.RestrictedTopicValidationRequest(**data)

        validation_result = validation_engine.run_validator(
            schemas.ValidatorType.RESTRICTED_TOPIC,
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
            schemas.ValidatorType.PII,
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

        for validation_request in validated_request.validations:
            validation_result = validation_engine.run_validator(
                validation_request.type,
                validation_request.text,
                validation_request.config,
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

import logging

import flask
import pydantic

import opik_guardrails.api.validators as validators
import opik_guardrails.services.pii_detection as pii_detection
import opik_guardrails.services.topic_restriction as topic_restriction

LOGGER = logging.getLogger(__name__)

guardrails_blueprint = flask.Blueprint("api", __name__, url_prefix="/api")

topics_relevance_classifier = topic_restriction.TopicRelevanceClassifier()
pii_detector = pii_detection.PIIDetector()


@guardrails_blueprint.route("/validate-topic", methods=["POST"])
def validate_topic() -> flask.Response:
    try:
        data = flask.request.get_json()
        validated_request = validators.TopicClassificationRequest(**data)

        classification_result = topics_relevance_classifier.predict(
            text=validated_request.text,
            topics=validated_request.topics,
            threshold=validated_request.threshold,
        )

        response = {
            "validation_passed": len(classification_result.relevant_topics_scores) == 0,
            "relevant_topics_scores": classification_result.relevant_topics_scores,
            "scores": classification_result.scores,
        }

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
        validated_request = validators.PIIDetectionRequest(**data)

        detection_result = pii_detector.detect(
            text=validated_request.text,
            entities_to_detect=validated_request.entities,
            language=validated_request.language,
        )

        response = {
            "validation_passed": not detection_result.has_pii,
            "detected_entities": detection_result.detected_entities,
        }

        return flask.jsonify(response), 200

    except pydantic.ValidationError as validation_error:
        LOGGER.warning(f"Validation error: {validation_error.errors()}")
        return flask.jsonify(
            {"error": "Invalid input", "details": validation_error.errors()}
        ), 400

    except Exception as exception:
        LOGGER.error(f"PII detection failed: {str(exception)}", exc_info=True)
        flask.abort(500, description=f"PII detection failed: {str(exception)}")

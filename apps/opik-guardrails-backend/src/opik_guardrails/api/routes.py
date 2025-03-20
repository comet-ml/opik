import logging
import flask
import pydantic
from . import validators
from ..services import topic_restriction

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

guardrails_blueprint = flask.Blueprint("api", __name__, url_prefix="/api")

topics_relevance_classifier = topic_restriction.TopicRelevanceClassifier()


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
        logger.warning(f"Validation error: {validation_error.errors()}")
        return flask.jsonify(
            {"error": "Invalid input", "details": validation_error.errors()}
        ), 400

    except Exception as exception:
        logger.error(f"Classification failed: {str(exception)}", exc_info=True)
        flask.abort(500, description=f"Classification failed: {str(exception)}")

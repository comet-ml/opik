import logging
from opik import exceptions, logging_messages
from opik.evaluation.metrics import score_result
from opik.evaluation.metrics.llm_judges import parsing_helpers

LOGGER = logging.getLogger(__name__)


def parse_model_output(content: str, name: str) -> score_result.ScoreResult:
    try:
        dict_content = parsing_helpers.extract_json_content_or_raise(content)
        score = float(dict_content["score"])

        if not (0.0 <= score <= 1.0):
            raise exceptions.MetricComputationError(
                f"Hallucination score must be between 0.0 and 1.0, got {score}"
            )

        return score_result.ScoreResult(
            name=name,
            value=score,
            reason=str(dict_content["reason"]),
        )
    except Exception as e:
        LOGGER.error(f"Failed to parse model output: {e}", exc_info=True)
        raise exceptions.MetricComputationError(
            logging_messages.HALLUCINATION_DETECTION_FAILED
        )

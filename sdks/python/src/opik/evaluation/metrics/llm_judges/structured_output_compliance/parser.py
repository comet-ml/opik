import logging

from opik import exceptions
from opik.evaluation.metrics import score_result
from opik.evaluation.metrics.llm_judges import parsing_helpers

LOGGER = logging.getLogger(__name__)


def parse_model_output(content: str, name: str) -> score_result.ScoreResult:
    """
    Parses the model output and returns a ScoreResult.
    """
    try:
        dict_content = parsing_helpers.extract_json_content_or_raise(content)
        score = float(dict_content["score"])
        reason_list = dict_content.get("reason", [])

        if score not in [0.0, 1.0]:
            raise exceptions.MetricComputationError(
                f"StructuredOutputCompliance score must be 0.0 or 1.0, got {score}"
            )

        return score_result.ScoreResult(
            name=name,
            value=score,
            reason=". ".join(reason_list) if reason_list else None,
        )
    except Exception as e:
        LOGGER.error(f"Failed to parse model output for {name}: {e}", exc_info=True)
        raise exceptions.MetricComputationError(
            f"Failed to parse model output for {name}"
        )
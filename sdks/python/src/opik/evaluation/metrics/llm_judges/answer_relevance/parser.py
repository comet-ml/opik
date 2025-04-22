import logging
from opik import exceptions, logging_messages
from opik.evaluation.metrics import score_result
from opik.evaluation.metrics.llm_judges import parsing_helpers

LOGGER = logging.getLogger(__name__)


def parse_model_output(content: str, name: str) -> score_result.ScoreResult:
    try:
        dict_content = parsing_helpers.extract_json_content_or_raise(content)
        score: float = dict_content["answer_relevance_score"]

        if not (0.0 <= score <= 1.0):
            raise exceptions.MetricComputationError(
                f"Answer relevance score must be between 0.0 and 1.0, got {score}"
            )

        return score_result.ScoreResult(
            name=name, value=score, reason=dict_content["reason"]
        )
    except Exception as e:
        LOGGER.error(f"Failed to parse model output: {e}", exc_info=True)
        raise exceptions.MetricComputationError(
            logging_messages.ANSWER_RELEVANCE_SCORE_CALC_FAILED
        )

import logging
import opik.logging_messages as logging_messages
import opik.exceptions as exceptions
from opik.evaluation.metrics import score_result
from opik.evaluation.metrics.llm_judges import parsing_helpers

LOGGER = logging.getLogger(__name__)


def parse_model_output(content: str, name: str) -> score_result.ScoreResult:
    try:
        list_content = parsing_helpers.extract_json_content_or_raise(content)

        reason = ""
        score = 0.0

        for claim in list_content:
            claim_score = float(claim["score"])

            if not (0.0 <= claim_score <= 1.0):
                raise exceptions.MetricComputationError(
                    f"Factuality score must be between 0.0 and 1.0, got {claim_score}"
                )

            score += claim_score
            reason += claim["reason"] + "\n"

        score /= len(list_content)

        return score_result.ScoreResult(name=name, value=score, reason=reason)
    except Exception as e:
        LOGGER.error(f"Failed to parse model output: {e}", exc_info=True)
        raise exceptions.MetricComputationError(
            logging_messages.FACTUALITY_SCORE_CALC_FAILED
        )

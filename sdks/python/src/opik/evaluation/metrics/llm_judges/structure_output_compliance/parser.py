import logging
from opik import exceptions, logging_messages
from opik.evaluation.metrics import score_result
from opik.evaluation.metrics.llm_judges import parsing_helpers

LOGGER = logging.getLogger(__name__)

def parse_model_output(content: str, name: str) -> score_result.ScoreResult:
    """
    Parses the LLM output for the StructuredOutputCompliance metric.
    Expected LLM output format:
    {
        "score": true or false,
        "reason": ["reason 1", "reason 2"]
    }
    Returns:
        score_result.ScoreResult: Standardized score result.
    """
    try:
        dict_content = parsing_helpers.extract_json_content_or_raise(content)

        score = dict_content.get("score")
        reason = dict_content.get("reason")

        # Validate types
        if not isinstance(score, bool):
            raise exceptions.MetricComputationError(
                f"`score` must be a boolean (true/false), got: {score}"
            )

        if not isinstance(reason, list) or not all(isinstance(r, str) for r in reason):
            raise exceptions.MetricComputationError(
                "`reason` must be a list of strings"
            )

        return score_result.ScoreResult(
            name=name,
            value=1.0 if score else 0.0,
            reason="\n".join(reason)
        )

    except Exception as e:
        LOGGER.error(f"Failed to parse StructuredOutputCompliance output: {e}", exc_info=True)
        raise exceptions.MetricComputationError(
            logging_messages.STRUCTURED_OUTPUT_COMPLIANCE_FAILED
        )
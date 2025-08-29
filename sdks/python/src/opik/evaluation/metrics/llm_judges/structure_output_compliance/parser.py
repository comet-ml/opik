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

    Args:
        content (str): The raw output string from the LLM to be parsed.
        name (str): The name of the metric or evaluation context.

    Returns:
        score_result.ScoreResult: Standardized score result.

    Raises:
        opik.exceptions.MetricComputationError: If the output cannot be parsed or does not conform to the expected format.
    """
    try:
        dict_content = parsing_helpers.extract_json_content_or_raise(content)

        score = dict_content.get("score")
        reason_list = dict_content.get("reason")

        # Validate types
        if not isinstance(score, bool):
            raise exceptions.MetricComputationError(
                f"`score` must be a boolean (true/false), got: {score}"
            )

        # Validate reason: must be list of strings
        if not isinstance(reason_list, list) or not all(
            isinstance(r, str) for r in reason_list
        ):
            reason_list = []

        # Fallback if LLM did not provide reason
        reason_str = "\n".join(reason_list) if reason_list else "No reason provided"

        return score_result.ScoreResult(
            name=name, value=1.0 if score else 0.0, reason=reason_str
        )

    except Exception as e:
        LOGGER.error(
            f"Failed to parse StructuredOutputCompliance output: {e}", exc_info=True
        )
        raise exceptions.MetricComputationError(
            logging_messages.STRUCTURED_OUTPUT_COMPLIANCE_FAILED
        )

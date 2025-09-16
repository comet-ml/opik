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

        # Check for required fields
        if "score" not in dict_content:
            raise exceptions.MetricComputationError(
                logging_messages.STRUCTURED_OUTPUT_COMPLIANCE_FAILED
            )

        if "reason" not in dict_content:
            raise exceptions.MetricComputationError(
                logging_messages.STRUCTURED_OUTPUT_COMPLIANCE_FAILED
            )

        score = dict_content["score"]
        reason_list = dict_content["reason"]

        # Validate types
        if not isinstance(score, bool):
            raise exceptions.MetricComputationError(
                logging_messages.STRUCTURED_OUTPUT_COMPLIANCE_FAILED
            )

        # Validate reason: must be list of strings
        if not isinstance(reason_list, list):
            raise exceptions.MetricComputationError(
                logging_messages.STRUCTURED_OUTPUT_COMPLIANCE_FAILED
            )

        if not all(isinstance(r, str) for r in reason_list):
            raise exceptions.MetricComputationError(
                logging_messages.STRUCTURED_OUTPUT_COMPLIANCE_FAILED
            )

        # Fallback if LLM did not provide reason
        reason_str = "\n".join(reason_list) if reason_list else "No reason provided"

        return score_result.ScoreResult(
            name=name, value=1.0 if score else 0.0, reason=reason_str
        )

    except exceptions.MetricComputationError:
        # Re-raise MetricComputationError as-is
        raise
    except Exception as e:
        LOGGER.error(
            f"Failed to parse StructuredOutputCompliance output: {e}", exc_info=True
        )
        raise exceptions.MetricComputationError(
            logging_messages.STRUCTURED_OUTPUT_COMPLIANCE_FAILED
        )

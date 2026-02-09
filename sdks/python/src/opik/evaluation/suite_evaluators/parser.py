import logging
from typing import List, Dict

from opik import exceptions
from opik.evaluation.metrics import score_result
from opik.evaluation.metrics.llm_judges import parsing_helpers

from . import template

LOGGER = logging.getLogger(__name__)


def parse_model_output(
    content: str,
    name: str,
    assertions: List[template.Assertion],
) -> List[score_result.ScoreResult]:
    """
    Parse the model output and return a list of ScoreResults.

    Assumes the model returns structured JSON output matching AssertionResultFormat.

    Args:
        content: The raw model output string (JSON formatted).
        name: The base name for the asserter (used as prefix for score names).
        assertions: The original assertions used for evaluation (to include in metadata).

    Returns:
        List of ScoreResult objects, one per assertion.

    Raises:
        MetricComputationError: If the response cannot be parsed.
    """
    try:
        parsed = parsing_helpers.extract_json_content_or_raise(content)
    except Exception as e:
        LOGGER.error("Failed to parse LLM judge response: %s", e, exc_info=True)
        raise exceptions.MetricComputationError(
            f"Failed to parse LLM judge response: {e}"
        )

    if "results" not in parsed or not isinstance(parsed["results"], list):
        raise exceptions.MetricComputationError(
            "LLM judge response missing 'results' array"
        )

    assertion_map: Dict[str, str] = {
        assertion["name"]: assertion["description"] for assertion in assertions
    }

    results: List[score_result.ScoreResult] = []

    for item in parsed["results"]:
        assertion_name = item.get("name", "unknown")
        result_value = item.get("value", 0)
        reason = item.get("reason", "")
        item_metadata = item.get("metadata", {})
        pass_score = item_metadata.get("pass_score", float(result_value))

        assertion_text = assertion_map.get(assertion_name, "")

        score_name = f"{name}_{assertion_name}" if name else assertion_name

        results.append(
            score_result.ScoreResult(
                name=score_name,
                value=float(result_value),
                reason=reason,
                metadata={
                    "pass_score": pass_score,
                    "assertion_text": assertion_text,
                },
            )
        )

    return results

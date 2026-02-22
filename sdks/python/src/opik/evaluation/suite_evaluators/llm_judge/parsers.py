"""
Parsing utilities for LLMJudge evaluator.

This module provides functions to build response format models and parse
LLM outputs into ScoreResult objects for the LLMJudge evaluator.

The response format aligns with the backend's OnlineScoringEngine:
each assertion produces {"score": <bool/int/float>, "reason": "..."}.
"""

import json
import logging
from typing import Any, List, Type

import pydantic

from opik.evaluation.metrics import score_result

LOGGER = logging.getLogger(__name__)


class AssertionResultItem(pydantic.BaseModel):
    """Result for a single assertion evaluation.

    Extends the backend's expected format {"score": <value>, "reason": "..."}
    with an additional ``confidence`` field used by the SDK.
    The backend ignores extra fields, so this is forward-compatible.
    """

    score: bool
    reason: str
    confidence: float = pydantic.Field(ge=0.0, le=1.0)


def build_response_format_model(
    assertions: List[str],
) -> Type[pydantic.BaseModel]:
    """
    Dynamically build a Pydantic model for structured output based on assertions.

    Creates a model with one field per assertion, using the assertion text as the
    field name. This ensures the LLM returns exactly one result per assertion
    with the correct name.

    Args:
        assertions: List of assertion strings to create fields for.

    Returns:
        A dynamically created Pydantic model class with one field per assertion.

    Example:
        >>> model = build_response_format_model(["Response is accurate", "Response is helpful"])
        >>> instance = model(**{
        ...     "Response is accurate": {"score": True, "reason": "Correct"},
        ...     "Response is helpful": {"score": True, "reason": "Helpful"},
        ... })
        >>> getattr(instance, "Response is accurate").score
        True
    """
    fields: dict[str, Any] = {
        assertion: (AssertionResultItem, ...) for assertion in assertions
    }
    return pydantic.create_model("LLMJudgeResponse", **fields)


def parse_model_output(
    content: str,
    assertions: List[str],
) -> List[score_result.ScoreResult]:
    """
    Parse the LLM model output JSON into a list of ScoreResult objects.

    Uses the dynamically built response model to validate the JSON structure
    and extract results for each assertion in the original order.

    Args:
        content: The raw JSON string output from the LLM.
        assertions: List of assertion strings that were evaluated.

    Returns:
        List of ScoreResult objects, one per assertion in the same order.
        If parsing fails, returns ScoreResult objects with scoring_failed=True.

    Example:
        >>> content = '{"Response is accurate": {"score": true, "reason": "Correct"}}'
        >>> results = parse_model_output(content, ["Response is accurate"])
        >>> results[0].name
        'Response is accurate'
        >>> results[0].value
        True
    """
    results: List[score_result.ScoreResult] = []
    response_model = build_response_format_model(assertions)

    try:
        parsed = json.loads(content)
        validated = response_model(**parsed)

        for assertion in assertions:
            item: AssertionResultItem = getattr(validated, assertion)
            results.append(
                score_result.ScoreResult(
                    name=assertion,
                    value=item.score,
                    reason=item.reason,
                    metadata={"confidence": item.confidence},
                )
            )

    except (json.JSONDecodeError, pydantic.ValidationError) as e:
        LOGGER.error(
            "Failed to parse LLMJudge model output: %s. Raw output: %s",
            e,
            content,
        )
        for assertion in assertions:
            results.append(
                score_result.ScoreResult(
                    name=assertion,
                    value=0.0,
                    reason=f"Failed to parse model output: {e}",
                    scoring_failed=True,
                    metadata={
                        "raw_output": content,
                    },
                )
            )

    return results

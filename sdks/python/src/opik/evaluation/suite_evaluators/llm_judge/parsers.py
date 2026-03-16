"""
Parsing utilities for LLMJudge evaluator.

This module provides functions to build response format models and parse
LLM outputs into ScoreResult objects for the LLMJudge evaluator.

The response format aligns with the backend's OnlineScoringEngine:
each assertion produces {"score": <bool/int/float>, "reason": "..."}.
"""

import json
import logging
import re
from typing import Any, Dict, List, Tuple, Type

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


def _sanitize_field_name(name: str) -> str:
    """Convert an arbitrary string into a valid Python / JSON-schema identifier.

    Replaces non-alphanumeric characters with underscores, collapses runs of
    underscores, and strips leading/trailing underscores.  Prepends ``a_`` if
    the result starts with a digit.
    """
    sanitized = re.sub(r"[^a-zA-Z0-9]", "_", name)
    sanitized = re.sub(r"_+", "_", sanitized).strip("_")
    if not sanitized or sanitized[0].isdigit():
        sanitized = f"a_{sanitized}"
    return sanitized


def _build_field_mapping(assertions: List[str]) -> Dict[str, str]:
    """Return a mapping from sanitized field name → original assertion text.

    Appends a numeric suffix when two assertions sanitize to the same key.
    """
    mapping: Dict[str, str] = {}
    for assertion in assertions:
        base = _sanitize_field_name(assertion)
        key = base
        counter = 2
        while key in mapping:
            key = f"{base}_{counter}"
            counter += 1
        mapping[key] = assertion
    return mapping


def build_response_format_model(
    assertions: List[str],
) -> Tuple[Type[pydantic.BaseModel], Dict[str, str]]:
    """
    Dynamically build a Pydantic model for structured output based on assertions.

    Field names are sanitized to alphanumeric identifiers so the schema is
    compatible with all LLM providers (OpenAI, Anthropic, etc.).

    Args:
        assertions: List of assertion strings to create fields for.

    Returns:
        A tuple of (model_class, field_mapping) where field_mapping maps
        sanitized field names back to original assertion text.

    Example:
        >>> model, mapping = build_response_format_model(["Response is accurate"])
        >>> list(mapping.values())
        ['Response is accurate']
    """
    field_mapping = _build_field_mapping(assertions)
    fields: dict[str, Any] = {key: (AssertionResultItem, ...) for key in field_mapping}
    return pydantic.create_model("LLMJudgeResponse", **fields), field_mapping


def parse_model_output(
    content: str,
    assertions: List[str],
    field_mapping: Dict[str, str],
) -> List[score_result.ScoreResult]:
    """
    Parse the LLM model output JSON into a list of ScoreResult objects.

    Uses the dynamically built response model to validate the JSON structure
    and extract results for each assertion in the original order.

    Args:
        content: The raw JSON string output from the LLM.
        assertions: List of assertion strings that were evaluated.
        field_mapping: Mapping from sanitized field names to original assertion text.

    Returns:
        List of ScoreResult objects, one per assertion in the same order.
        If parsing fails, returns ScoreResult objects with scoring_failed=True.
    """
    results: List[score_result.ScoreResult] = []
    response_model, _ = build_response_format_model(assertions)
    key_to_assertion = field_mapping

    try:
        parsed = json.loads(content)
        validated = response_model(**parsed)

        for field_key, assertion in key_to_assertion.items():
            item: AssertionResultItem = getattr(validated, field_key)
            results.append(
                score_result.ScoreResult(
                    name=assertion,
                    value=item.score,
                    reason=item.reason,
                    category_name="suite_assertion",
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
                    category_name="suite_assertion",
                    scoring_failed=True,
                    metadata={
                        "raw_output": content,
                    },
                )
            )

    return results

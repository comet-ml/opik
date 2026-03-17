"""
Parsing utilities for LLMJudge evaluator.

This module provides the ResponseSchema class that builds response format models
and parses LLM outputs into ScoreResult objects for the LLMJudge evaluator.

The response format aligns with the backend's OnlineScoringEngine:
each assertion produces {"score": <bool/int/float>, "reason": "..."}.
"""

import json
import logging
from typing import Any, Dict, List, Type

import pydantic

from opik.evaluation.metrics import score_result

LOGGER = logging.getLogger(__name__)


# Result for a single assertion evaluation.
# Uses a comment instead of a docstring to avoid leaking into the JSON schema
# sent to providers.
class AssertionResultItem(pydantic.BaseModel):
    score: bool
    reason: str
    confidence: float = pydantic.Field(ge=0.0, le=1.0)


class ResponseSchema:
    """Encapsulates the JSON schema for LLMJudge structured output.

    Uses short indexed keys (``assertion_1``, ``assertion_2``, ...) that are
    compatible with all LLM providers while embedding the original assertion
    text as the field ``description`` in the JSON schema.
    """

    def __init__(self, assertions: List[str]) -> None:
        self._assertions = list(assertions)
        self._field_mapping: Dict[str, str] = {
            f"assertion_{i}": assertion for i, assertion in enumerate(assertions, 1)
        }
        fields: dict[str, Any] = {
            key: (
                AssertionResultItem,
                pydantic.Field(description=assertion),
            )
            for key, assertion in self._field_mapping.items()
        }
        self._response_model: Type[pydantic.BaseModel] = pydantic.create_model(
            "LLMJudgeResponse", **fields
        )

    @property
    def response_format(self) -> Type[pydantic.BaseModel]:
        return self._response_model

    def format_assertions(self) -> str:
        return "\n".join(
            f"- `{key}`: {assertion}" for key, assertion in self._field_mapping.items()
        )

    def parse(self, content: str) -> List[score_result.ScoreResult]:
        """Parse the LLM model output JSON into a list of ScoreResult objects.

        Returns ScoreResult objects with scoring_failed=True if parsing fails.
        """
        try:
            parsed = json.loads(content)
            validated = self._response_model(**parsed)

            return [
                score_result.ScoreResult(
                    name=assertion,
                    value=item.score,
                    reason=item.reason,
                    category_name="suite_assertion",
                    metadata={"confidence": item.confidence},
                )
                for field_key, assertion in self._field_mapping.items()
                for item in [getattr(validated, field_key)]
            ]

        except (json.JSONDecodeError, pydantic.ValidationError) as e:
            LOGGER.error(
                "Failed to parse LLMJudge model output: %s. Raw output: %s",
                e,
                content,
            )
            return [
                score_result.ScoreResult(
                    name=assertion,
                    value=0.0,
                    reason=f"Failed to parse model output: {e}",
                    category_name="suite_assertion",
                    scoring_failed=True,
                    metadata={"raw_output": content},
                )
                for assertion in self._field_mapping.values()
            ]

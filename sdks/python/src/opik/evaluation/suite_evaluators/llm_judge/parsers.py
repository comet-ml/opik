"""
Parsing utilities for LLMJudge evaluator.

This module provides the ResponseSchema class that builds response format models
and parses LLM outputs into ScoreResult objects for the LLMJudge evaluator.

The response format aligns with the backend's OnlineScoringEngine:
each assertion produces {"score": <bool/int/float>, "reason": "..."}.
"""

import copy
import json
import logging
from typing import Any, Dict, List, Type

import pydantic

from opik.evaluation.metrics import score_result
from opik.exceptions import LLMJudgeParseError

LOGGER = logging.getLogger(__name__)


def _resolve_refs(schema: Dict[str, Any]) -> Dict[str, Any]:
    """Inline all $ref references and remove the $defs block.

    Anthropic's grammar-constrained decoding struggles with $ref indirection,
    especially on smaller models (e.g. Haiku). Inlining produces a flat schema
    that constrains output more reliably.

    Handles nested $refs recursively, merges sibling keys (like ``description``)
    into the resolved object, uses deepcopy to avoid shared mutable state,
    and strips ``title`` keys that add noise.
    """
    defs = schema.get("$defs", {})
    _MAX_DEPTH = 50

    def _resolve_node(node: Any, depth: int = 0) -> Any:
        if depth > _MAX_DEPTH:
            return node

        if isinstance(node, list):
            return [_resolve_node(item, depth + 1) for item in node]

        if not isinstance(node, dict):
            return node

        if "$ref" in node:
            ref_path = node["$ref"]
            # Only handle local #/$defs/ references
            if ref_path.startswith("#/$defs/"):
                def_name = ref_path[len("#/$defs/") :]
                resolved = copy.deepcopy(defs[def_name])
                resolved = _resolve_node(resolved, depth + 1)
                # Merge sibling keys (e.g. description) into the resolved object
                for key, value in node.items():
                    if key != "$ref":
                        resolved[key] = value
                resolved.pop("title", None)
                return resolved

        result = {}
        for key, value in node.items():
            if key == "title":
                continue
            result[key] = _resolve_node(value, depth + 1)
        return result

    resolved = _resolve_node(schema)
    resolved.pop("$defs", None)
    return resolved


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
        base_model = pydantic.create_model("LLMJudgeResponse", **fields)
        resolved = _resolve_refs(base_model.model_json_schema())

        # Override model_json_schema so providers receive the flat schema
        @classmethod  # type: ignore[misc]
        def _resolved_schema(cls, **_kwargs: Any) -> Dict[str, Any]:  # type: ignore[no-untyped-def]
            return resolved

        base_model.model_json_schema = _resolved_schema  # type: ignore[assignment]
        self._response_model: Type[pydantic.BaseModel] = base_model

    @property
    def response_format(self) -> Type[pydantic.BaseModel]:
        return self._response_model

    def format_assertions(self) -> str:
        return "\n".join(
            f"- `{key}`: {assertion}" for key, assertion in self._field_mapping.items()
        )

    def parse(self, content: str) -> List[score_result.ScoreResult]:
        """Parse and validate the LLM model output JSON into ScoreResult objects.

        Raises LLMJudgeParseError (with partial results attached) when parsing
        or validation fails, so callers can retry or fall back gracefully.
        """
        if content is None:
            raise LLMJudgeParseError(
                results=[
                    score_result.ScoreResult(
                        name=assertion,
                        value=0.0,
                        reason="Model returned no output",
                        category_name="suite_assertion",
                        scoring_failed=True,
                    )
                    for assertion in self._field_mapping.values()
                ],
                message="Model returned None output",
            )

        try:
            parsed = json.loads(content)
            validated = self._response_model(**parsed)

            results = [
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
            results = [
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

        expected = len(self._field_mapping)

        if len(results) != expected:
            raise LLMJudgeParseError(
                results=results,
                message=f"Expected {expected} results, got {len(results)}",
            )

        failed = [r for r in results if r.scoring_failed]
        if failed:
            names = [r.name for r in failed]
            raise LLMJudgeParseError(
                results=results,
                message=f"{len(failed)} of {expected} assertions failed to parse: {names}",
            )

        return results

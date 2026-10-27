"""
Unit tests for parsing and structured-output behaviors in opik_optimizer.core.llm_calls.
"""

from __future__ import annotations

from typing import Any, cast
from unittest.mock import MagicMock

import pytest
from pydantic import BaseModel, ValidationError

from opik_optimizer.core.llm_calls import _parse_response, StructuredOutputParsingError
from tests.unit.test_helpers import make_mock_response


class _NameCountModel(BaseModel):
    name: str
    count: int


class _NameModel(BaseModel):
    name: str


class _FieldModel(BaseModel):
    field: str


class _RequiredFieldModel(BaseModel):
    required_field: str


def _assert_pydantic_model(
    result: Any, model: type[BaseModel], expected: dict[str, Any]
) -> None:
    assert isinstance(result, model)
    for key, value in expected.items():
        assert getattr(result, key) == value


def _role_of(message: Any) -> str | None:
    if isinstance(message, dict):
        value = message.get("role")
        return value if isinstance(value, str) else None
    value = getattr(message, "role", None)
    return value if isinstance(value, str) else None


class TestParseResponse:
    """Tests for _parse_response function."""

    def test_parses_structured_output_with_response_model(self) -> None:
        mock_response = make_mock_response('{"name": "test", "count": 42}')
        result = _parse_response(mock_response, response_model=_NameCountModel)
        _assert_pydantic_model(result, _NameCountModel, {"name": "test", "count": 42})

    def test_parses_structured_output_from_parsed_object(self) -> None:
        mock_response = make_mock_response(
            "not valid json",
            parsed={"name": "parsed", "count": 7},
        )
        result = _parse_response(mock_response, response_model=_NameCountModel)
        _assert_pydantic_model(result, _NameCountModel, {"name": "parsed", "count": 7})

    def test_parsed_object_invalid_falls_back_to_json(self) -> None:
        mock_response = make_mock_response(
            '{"name": "json", "count": 11}',
            parsed={"name": "missing_count"},
        )
        result = _parse_response(mock_response, response_model=_NameCountModel)
        _assert_pydantic_model(result, _NameCountModel, {"name": "json", "count": 11})

    @pytest.mark.parametrize(
        "finish_reason,content",
        [
            ("length", ""),
            ("max_tokens", "   "),
            ("token limit", ""),
        ],
    )
    def test_raises_bad_request_error_on_truncation_with_empty_content(
        self, finish_reason: str, content: str
    ) -> None:
        from litellm.exceptions import BadRequestError

        mock_response = make_mock_response(content, finish_reason=finish_reason)

        with pytest.raises(BadRequestError) as exc_info:
            _parse_response(mock_response)

        if finish_reason == "length":
            assert "max_tokens" in str(exc_info.value)

    def test_does_not_raise_on_truncation_with_non_empty_content(self) -> None:
        mock_response = make_mock_response(
            "Some partial content", finish_reason="length"
        )
        assert _parse_response(mock_response) == "Some partial content"

    def test_raises_structured_output_parsing_error_on_invalid_json(self) -> None:
        mock_response = make_mock_response("not valid json")
        with pytest.raises(StructuredOutputParsingError) as exc_info:
            _parse_response(mock_response, response_model=_FieldModel)
        assert exc_info.value.content == "not valid json"

    def test_raises_structured_output_parsing_error_on_schema_mismatch(self) -> None:
        mock_response = make_mock_response('{"wrong_field": "value"}')
        with pytest.raises(StructuredOutputParsingError) as exc_info:
            _parse_response(mock_response, response_model=_RequiredFieldModel)
        assert "required_field" in str(exc_info.value.content) or isinstance(
            exc_info.value.error, ValidationError
        )

    def test_fallback_parsing_with_python_repr(self) -> None:
        mock_response = make_mock_response("{'name': 'test'}")
        result = _parse_response(mock_response, response_model=_NameModel)
        _assert_pydantic_model(result, _NameModel, {"name": "test"})

    def test_return_all_prefers_parsed_objects(self) -> None:
        mock_response = MagicMock()
        mock_response.choices = [MagicMock(), MagicMock()]
        mock_response.choices[0].message.content = "invalid"
        mock_response.choices[0].message.parsed = {"name": "first"}
        mock_response.choices[1].message.content = "invalid"
        mock_response.choices[1].message.parsed = {"name": "second"}

        result = _parse_response(
            mock_response, response_model=_NameModel, return_all=True
        )

        parsed_results = cast(list[_NameModel], result)
        assert [item.name for item in parsed_results] == ["first", "second"]


class TestStructuredOutputModels:
    """Tests for structured output response models used by optimizers."""

    @pytest.mark.parametrize(
        "payload,expected_patterns,expected_first",
        [
            (
                [
                    {"pattern": "Be concise", "example": "Short answers"},
                    {"pattern": "Use citations"},
                ],
                None,
                "Be concise",
            ),
            (["Pattern A", "Pattern B"], ["Pattern A", "Pattern B"], None),
        ],
    )
    def test_pattern_extraction_response_normalizes_payloads(
        self,
        payload: Any,
        expected_patterns: list[str] | None,
        expected_first: str | None,
    ) -> None:
        from opik_optimizer.algorithms.meta_prompt_optimizer.types import (
            PatternExtractionResponse,
        )

        parsed = PatternExtractionResponse.model_validate(payload)

        if expected_patterns is not None:
            assert parsed.patterns == expected_patterns
            return

        assert len(parsed.patterns) == 2
        first = parsed.patterns[0]
        pattern_text = first.pattern if hasattr(first, "pattern") else first
        assert pattern_text == expected_first

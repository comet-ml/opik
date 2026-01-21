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

    def test_returns_content_when_no_response_model(self) -> None:
        mock_response = make_mock_response("Hello, world!")
        assert _parse_response(mock_response) == "Hello, world!"

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

    def test_parses_structured_output_from_parsed_model_instance(self) -> None:
        parsed_model = _NameCountModel(name="parsed", count=3)
        mock_response = make_mock_response("{}", parsed=parsed_model)
        result = _parse_response(mock_response, response_model=_NameCountModel)
        assert result is parsed_model

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

    def test_handles_none_finish_reason(self) -> None:
        mock_response = make_mock_response("Response content")
        del mock_response.choices[0].finish_reason
        assert _parse_response(mock_response) == "Response content"

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
        "payload,expected_len,expected_first_role",
        [
            (
                [{"role": "system", "content": "s"}, {"role": "user", "content": "u"}],
                2,
                "system",
            ),
            (
                {
                    "messages": [
                        {"role": "system", "content": "s"},
                        {"role": "user", "content": "u"},
                    ]
                },
                2,
                "system",
            ),
            ({"role": "system", "content": "s"}, 1, "system"),
        ],
    )
    def test_mutation_response_normalizes_payloads(
        self, payload: Any, expected_len: int, expected_first_role: str
    ) -> None:
        from opik_optimizer.algorithms.evolutionary_optimizer.types import (
            MutationResponse,
        )

        parsed = MutationResponse.model_validate(payload)

        assert len(parsed.messages) == expected_len
        assert _role_of(parsed.messages[0]) == expected_first_role

    @pytest.mark.parametrize(
        "payload",
        [
            (
                [
                    {
                        "prompt": [
                            {"role": "system", "content": "s"},
                            {"role": "user", "content": "u"},
                        ]
                    }
                ]
            ),
            (
                {
                    "prompts": [
                        {
                            "prompt": [
                                {"role": "system", "content": "s"},
                                {"role": "user", "content": "u"},
                            ]
                        }
                    ]
                }
            ),
        ],
    )
    def test_prompt_candidates_response_normalizes_payloads(self, payload: Any) -> None:
        from opik_optimizer.algorithms.meta_prompt_optimizer.types import (
            PromptCandidatesResponse,
        )

        parsed = PromptCandidatesResponse.model_validate(payload)

        assert len(parsed.prompts) == 1
        assert _role_of(parsed.prompts[0].prompt[0]) == "system"

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


class TestStructuredOutputParsingError:
    def test_stores_content_and_error(self) -> None:
        original_error = ValueError("Original error")
        exc = StructuredOutputParsingError(
            content="failed content", error=original_error
        )
        assert exc.content == "failed content"
        assert exc.error is original_error

    def test_message_includes_content_and_error(self) -> None:
        original_error = ValueError("Parse failed")
        exc = StructuredOutputParsingError(
            content="{'bad': json}", error=original_error
        )
        message = str(exc)
        assert "Parse failed" in message
        assert "{'bad': json}" in message

    def test_can_be_caught_as_exception(self) -> None:
        with pytest.raises(Exception):
            raise StructuredOutputParsingError(
                content="content", error=ValueError("error")
            )


class TestParseResponseEdgeCases:
    def test_parse_response_handles_nested_pydantic_model(self) -> None:
        class Inner(BaseModel):
            value: int

        class Outer(BaseModel):
            inner: Inner
            name: str

        mock_response = make_mock_response('{"inner": {"value": 42}, "name": "test"}')

        result = _parse_response(mock_response, response_model=Outer)

        assert isinstance(result, Outer)
        assert result.inner.value == 42
        assert result.name == "test"

    def test_parse_response_handles_list_in_pydantic_model(self) -> None:
        class ListModel(BaseModel):
            items: list[str]

        mock_response = make_mock_response('{"items": ["a", "b", "c"]}')

        result = _parse_response(mock_response, response_model=ListModel)

        assert isinstance(result, ListModel)
        assert result.items == ["a", "b", "c"]

    def test_parse_response_handles_unicode_content(self) -> None:
        mock_response = make_mock_response("Hello, 世界! 🌍")
        assert _parse_response(mock_response) == "Hello, 世界! 🌍"

"""
Unit tests for call_model/call_model_async and related utilities in opik_optimizer.core.llm_calls.
"""

from __future__ import annotations

from typing import Any
from unittest.mock import MagicMock, patch

import pytest
from pydantic import BaseModel

from opik_optimizer.core import llm_calls as _llm_calls
from tests.unit.fixtures import user_message
from tests.unit.test_helpers import make_mock_response


def _assert_json_retry_instructions_injected(
    captured_messages: list[list[dict[str, str]]],
) -> None:
    assert len(captured_messages) == 2
    assert "STRICT OUTPUT FORMAT" in captured_messages[1][-1]["content"]


class SampleResponseModel(BaseModel):
    """Sample Pydantic model for testing structured output."""

    name: str
    score: float


class TestCallModelSync:
    def test_call_model_uses_strict_response_schema_for_openai(self) -> None:
        captured_kwargs: dict[str, Any] = {}
        mock_response = make_mock_response(
            '{"inner": {"value": 1, "detail": null}, "note": null}'
        )

        class Inner(BaseModel):
            value: int
            detail: str | None = None

        class Outer(BaseModel):
            inner: Inner
            note: str | None = None

        def capture_completion(**kwargs: Any) -> MagicMock:
            captured_kwargs.update(kwargs)
            return mock_response

        with patch("opik_optimizer.core.llm_calls.track_completion") as mock_track:
            mock_track.return_value = lambda x: capture_completion

            result = _llm_calls.call_model(
                messages=[user_message("test")],
                model="gpt-4o",
                response_model=Outer,
            )

        assert isinstance(result, Outer)
        assert result.inner.value == 1
        assert result.inner.detail is None
        assert result.note is None
        response_format = captured_kwargs.get("response_format", {})
        schema = response_format.get("json_schema", {}).get("schema", {})
        assert schema.get("additionalProperties") is False
        assert schema.get("required") == ["inner", "note"]
        assert schema.get("$defs", {}).get("Inner", {}).get("additionalProperties") is (
            False
        )
        assert schema.get("$defs", {}).get("Inner", {}).get("required") == [
            "value",
            "detail",
        ]

    def test_call_model_retries_with_json_instructions(self) -> None:
        captured_messages: list[list[dict[str, str]]] = []
        call_count = {"n": 0}

        def mock_completion(**kwargs: Any) -> MagicMock:
            captured_messages.append(kwargs["messages"])
            if call_count["n"] == 0:
                call_count["n"] += 1
                return make_mock_response("not json")
            return make_mock_response('{"name": "retry", "score": 1.0}')

        with patch("opik_optimizer.core.llm_calls.track_completion") as mock_track:
            mock_track.return_value = lambda x: mock_completion

            result = _llm_calls.call_model(
                messages=[user_message("test")],
                model="gpt-4o",
                response_model=SampleResponseModel,
            )

            assert isinstance(result, SampleResponseModel)
            assert result.name == "retry"
            _assert_json_retry_instructions_injected(captured_messages)

    def test_call_model_preserves_model_parameter_response_format(self) -> None:
        mock_response = make_mock_response('{"name": "test", "score": 0.9}')
        captured_kwargs: dict[str, Any] = {}
        custom_response_format = {"type": "json_object"}

        def capture_completion(**kwargs: Any) -> Any:
            captured_kwargs.update(kwargs)
            return mock_response

        with patch("opik_optimizer.core.llm_calls.track_completion") as mock_track:
            mock_track.return_value = lambda x: capture_completion
            result = _llm_calls.call_model(
                messages=[user_message("test")],
                model="gpt-4o",
                response_model=SampleResponseModel,
                model_parameters={"response_format": custom_response_format},
            )

        assert isinstance(result, SampleResponseModel)
        assert captured_kwargs.get("response_format") == custom_response_format


class TestCallModelAsync:
    @pytest.mark.asyncio
    async def test_call_model_async_uses_strict_response_schema_for_openai(
        self,
    ) -> None:
        captured_kwargs: dict[str, Any] = {}
        mock_response = make_mock_response(
            '{"inner": {"value": 1, "detail": null}, "note": null}'
        )

        class Inner(BaseModel):
            value: int
            detail: str | None = None

        class Outer(BaseModel):
            inner: Inner
            note: str | None = None

        async def capture_completion(**kwargs: Any) -> Any:
            captured_kwargs.update(kwargs)
            return mock_response

        with patch("opik_optimizer.core.llm_calls.track_completion") as mock_track:
            mock_track.return_value = lambda x: capture_completion

            result = await _llm_calls.call_model_async(
                messages=[user_message("test")],
                model="gpt-4o",
                response_model=Outer,
            )

        assert isinstance(result, Outer)
        assert result.inner.value == 1
        assert result.inner.detail is None
        assert result.note is None
        response_format = captured_kwargs.get("response_format", {})
        schema = response_format.get("json_schema", {}).get("schema", {})
        assert schema.get("additionalProperties") is False
        assert schema.get("required") == ["inner", "note"]
        assert schema.get("$defs", {}).get("Inner", {}).get("additionalProperties") is (
            False
        )
        assert schema.get("$defs", {}).get("Inner", {}).get("required") == [
            "value",
            "detail",
        ]

    @pytest.mark.asyncio
    async def test_call_model_async_retries_with_json_instructions(self) -> None:
        captured_messages: list[list[dict[str, str]]] = []
        call_count = {"n": 0}

        async def mock_completion(**kwargs: Any) -> MagicMock:
            captured_messages.append(kwargs["messages"])
            if call_count["n"] == 0:
                call_count["n"] += 1
                return make_mock_response("not json")
            return make_mock_response('{"name": "retry", "score": 1.0}')

        with patch("opik_optimizer.core.llm_calls.track_completion") as mock_track:
            mock_track.return_value = lambda x: mock_completion

            result = await _llm_calls.call_model_async(
                messages=[user_message("test")],
                model="gpt-4o",
                response_model=SampleResponseModel,
            )

            assert isinstance(result, SampleResponseModel)
            assert result.name == "retry"
            _assert_json_retry_instructions_injected(captured_messages)

    @pytest.mark.asyncio
    async def test_call_model_async_preserves_model_parameter_response_format(
        self,
    ) -> None:
        mock_response = make_mock_response('{"name": "test", "score": 0.9}')
        captured_kwargs: dict[str, Any] = {}
        custom_response_format = {"type": "json_object"}

        async def capture_completion(**kwargs: Any) -> Any:
            captured_kwargs.update(kwargs)
            return mock_response

        with patch("opik_optimizer.core.llm_calls.track_completion") as mock_track:
            mock_track.return_value = lambda x: capture_completion
            result = await _llm_calls.call_model_async(
                messages=[user_message("test")],
                model="gpt-4o",
                response_model=SampleResponseModel,
                model_parameters={"response_format": custom_response_format},
            )

        assert isinstance(result, SampleResponseModel)
        assert captured_kwargs.get("response_format") == custom_response_format

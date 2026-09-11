"""
Unit tests for call_model/call_model_async and related utilities in opik_optimizer.core.llm_calls.
"""

from __future__ import annotations

from collections.abc import Callable
from typing import Any
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from pydantic import BaseModel

from opik_optimizer.core import llm_calls as _llm_calls
from opik_optimizer.base_optimizer import BaseOptimizer
from opik_optimizer.core.state import OptimizationContext
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

    def test_call_model_increments_counter(self) -> None:
        mock_response = make_mock_response("response")

        with patch(
            "opik_optimizer.core.llm_calls._increment_llm_counter_if_in_optimizer"
        ) as mock_inc:
            with patch("opik_optimizer.core.llm_calls.track_completion") as mock_track:
                mock_track.return_value = lambda x: x
                with patch("litellm.completion", return_value=mock_response):
                    _llm_calls.call_model(
                        messages=[user_message("test")],
                        model="gpt-4o",
                    )
                    mock_inc.assert_called_once()

    def test_call_model_with_structured_output(self) -> None:
        mock_response = make_mock_response('{"name": "test", "score": 0.9}')

        with patch("opik_optimizer.core.llm_calls.track_completion") as mock_track:
            mock_completion = MagicMock(return_value=mock_response)
            mock_track.return_value = lambda x: mock_completion

            result = _llm_calls.call_model(
                messages=[user_message("test")],
                model="gpt-4o",
                response_model=SampleResponseModel,
            )

            assert isinstance(result, SampleResponseModel)
            assert result.name == "test"

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
    async def test_call_model_async_increments_counter(self) -> None:
        mock_response = make_mock_response("response")

        with patch(
            "opik_optimizer.core.llm_calls._increment_llm_counter_if_in_optimizer"
        ) as mock_inc:
            with patch("opik_optimizer.core.llm_calls.track_completion") as mock_track:
                async_mock = AsyncMock(return_value=mock_response)
                mock_track.return_value = lambda x: async_mock

                await _llm_calls.call_model_async(
                    messages=[user_message("test")],
                    model="gpt-4o",
                )
                mock_inc.assert_called_once()

    @pytest.mark.asyncio
    async def test_call_model_async_with_structured_output(self) -> None:
        mock_response = make_mock_response('{"name": "test", "score": 0.9}')

        with patch("opik_optimizer.core.llm_calls.track_completion") as mock_track:
            async_mock = AsyncMock(return_value=mock_response)
            mock_track.return_value = lambda x: async_mock

            result = await _llm_calls.call_model_async(
                messages=[user_message("test")],
                model="gpt-4o",
                response_model=SampleResponseModel,
            )

            assert isinstance(result, SampleResponseModel)
            assert result.name == "test"

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

    @pytest.mark.asyncio
    async def test_call_model_async_passes_model_parameters(self) -> None:
        mock_response = make_mock_response("response")
        captured_kwargs: dict[str, Any] = {}

        async def capture_call(**kwargs: Any) -> Any:
            captured_kwargs.update(kwargs)
            return mock_response

        with patch("opik_optimizer.core.llm_calls.track_completion") as mock_track:
            mock_track.return_value = lambda x: capture_call

            await _llm_calls.call_model_async(
                messages=[user_message("test")],
                model="gpt-4o",
                model_parameters={"temperature": 0.5},
                temperature=0.7,
            )

            assert captured_kwargs["temperature"] == 0.7

    @pytest.mark.asyncio
    async def test_call_model_async_project_name_passed(self) -> None:
        mock_response = make_mock_response("response")
        captured_project: str | None = None

        def capture_track(
            project_name: str | None = None,
        ) -> Callable[[Any], AsyncMock]:
            nonlocal captured_project
            captured_project = project_name
            return lambda x: AsyncMock(return_value=mock_response)

        with patch(
            "opik_optimizer.core.llm_calls.track_completion", side_effect=capture_track
        ):
            await _llm_calls.call_model_async(
                messages=[user_message("test")],
                model="gpt-4o",
                project_name="my-project",
            )

            assert captured_project == "my-project"


class TestStripProjectName:
    def test_strip_removes_project_name(self) -> None:
        params: dict[str, Any] = {
            "model": "gpt-4o",
            "metadata": {"opik": {"project_name": "test", "tags": ["tag1"]}},
        }

        result: dict[str, Any] = _llm_calls._strip_project_name(params)

        assert "project_name" in params["metadata"]["opik"]
        assert "project_name" not in result["metadata"]["opik"]
        assert result["metadata"]["opik"]["tags"] == ["tag1"]

    def test_strip_handles_missing_metadata(self) -> None:
        params = {"model": "gpt-4o"}
        result = _llm_calls._strip_project_name(params)
        assert result == params

    def test_strip_handles_empty_opik(self) -> None:
        params = {"metadata": {"opik": {"project_name": "test"}}}
        result = _llm_calls._strip_project_name(params)
        assert "opik" not in result["metadata"]


class TestCounterIncrement:
    def test_increment_llm_counter_walks_stack(self) -> None:
        class MockOptimizer(BaseOptimizer):
            DEFAULT_PROMPTS: dict[str, str] = {}

            def optimize_prompt(self, *args: Any, **kwargs: Any) -> Any:
                pass

            def run_optimization(self, context: OptimizationContext) -> Any:
                pass

            def get_config(self, context: OptimizationContext) -> dict[str, Any]:
                return {"optimizer": "MockOptimizer"}

            def get_optimizer_metadata(self) -> dict[str, Any]:
                return {}

        optimizer = MockOptimizer(model="gpt-4o")
        initial_count = optimizer.llm_call_counter

        def inner_call(self: Any) -> None:
            _llm_calls._increment_llm_counter_if_in_optimizer()

        inner_call(optimizer)

        assert optimizer.llm_call_counter == initial_count + 1

    def test_increment_llm_call_tools_counter_walks_stack(self) -> None:
        class MockOptimizer(BaseOptimizer):
            DEFAULT_PROMPTS: dict[str, str] = {}

            def optimize_prompt(self, *args: Any, **kwargs: Any) -> Any:
                pass

            def run_optimization(self, context: OptimizationContext) -> Any:
                pass

            def get_config(self, context: OptimizationContext) -> dict[str, Any]:
                return {"optimizer": "MockOptimizer"}

            def get_optimizer_metadata(self) -> dict[str, Any]:
                return {}

        optimizer = MockOptimizer(model="gpt-4o")
        initial_count = optimizer.llm_call_tools_counter

        def inner_call(self: Any) -> None:
            _llm_calls._increment_llm_call_tools_counter_if_in_optimizer()

        inner_call(optimizer)

        assert optimizer.llm_call_tools_counter == initial_count + 1

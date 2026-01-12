"""Unit tests for _llm_calls module async and sync wiring."""

from __future__ import annotations

import json
from typing import Any
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from pydantic import BaseModel

from opik_optimizer import _llm_calls


class SampleResponseModel(BaseModel):
    """Sample Pydantic model for testing structured output."""

    name: str
    score: float


class NestedResponseModel(BaseModel):
    """Nested model for testing complex structures."""

    items: list[SampleResponseModel]
    total: int


class TestParseResponse:
    """Test response parsing logic."""

    def test_parse_raw_string_response(self) -> None:
        """Test parsing raw string response without model."""
        mock_response = MagicMock()
        mock_response.choices = [MagicMock()]
        mock_response.choices[0].message.content = "Hello, world!"
        mock_response.choices[0].finish_reason = "stop"

        result = _llm_calls._parse_response(mock_response, response_model=None)
        assert result == "Hello, world!"

    def test_parse_structured_response(self) -> None:
        """Test parsing structured JSON response into Pydantic model."""
        mock_response = MagicMock()
        mock_response.choices = [MagicMock()]
        mock_response.choices[0].message.content = '{"name": "test", "score": 0.95}'
        mock_response.choices[0].finish_reason = "stop"

        result = _llm_calls._parse_response(
            mock_response, response_model=SampleResponseModel
        )
        assert isinstance(result, SampleResponseModel)
        assert result.name == "test"
        assert result.score == 0.95

    def test_parse_nested_structured_response(self) -> None:
        """Test parsing nested structured response."""
        mock_response = MagicMock()
        content = json.dumps({
            "items": [
                {"name": "item1", "score": 0.8},
                {"name": "item2", "score": 0.9},
            ],
            "total": 2,
        })
        mock_response.choices = [MagicMock()]
        mock_response.choices[0].message.content = content
        mock_response.choices[0].finish_reason = "stop"

        result = _llm_calls._parse_response(
            mock_response, response_model=NestedResponseModel
        )
        assert isinstance(result, NestedResponseModel)
        assert len(result.items) == 2
        assert result.total == 2

    def test_parse_truncated_response_raises_error(self) -> None:
        """Test that truncated responses (max_tokens) raise BadRequestError."""
        from litellm.exceptions import BadRequestError

        mock_response = MagicMock()
        mock_response.choices = [MagicMock()]
        mock_response.choices[0].message.content = ""
        mock_response.choices[0].finish_reason = "length"  # Truncated
        mock_response.model = "gpt-4o"

        with pytest.raises(BadRequestError, match="max_tokens"):
            _llm_calls._parse_response(mock_response, response_model=None)

    def test_parse_invalid_json_raises_structured_error(self) -> None:
        """Test that invalid JSON raises StructuredOutputParsingError."""
        mock_response = MagicMock()
        mock_response.choices = [MagicMock()]
        mock_response.choices[0].message.content = "not valid json"
        mock_response.choices[0].finish_reason = "stop"

        with pytest.raises(_llm_calls.StructuredOutputParsingError):
            _llm_calls._parse_response(
                mock_response, response_model=SampleResponseModel
            )


class TestBuildCallTimeParams:
    """Test parameter building for LLM calls."""

    def test_build_params_with_all_values(self) -> None:
        """Test building params with all optional values."""
        params = _llm_calls._build_call_time_params(
            temperature=0.7,
            max_tokens=1000,
            max_completion_tokens=500,
            top_p=0.9,
            presence_penalty=0.1,
            frequency_penalty=0.2,
            metadata={"key": "value"},
        )

        assert params["temperature"] == 0.7
        assert params["max_tokens"] == 1000
        assert params["max_completion_tokens"] == 500
        assert params["top_p"] == 0.9
        assert params["presence_penalty"] == 0.1
        assert params["frequency_penalty"] == 0.2
        assert params["metadata"] == {"key": "value"}

    def test_build_params_excludes_none(self) -> None:
        """Test that None values are excluded from params."""
        params = _llm_calls._build_call_time_params(
            temperature=0.7,
            max_tokens=None,
        )

        assert "temperature" in params
        assert "max_tokens" not in params


class TestPrepareModelParams:
    """Test model parameter preparation."""

    def test_prepare_params_merges_correctly(self) -> None:
        """Test that model_parameters and call_time_params merge correctly."""
        model_parameters = {"temperature": 0.5, "top_p": 0.8}
        call_time_params = {"temperature": 0.7}  # Should override

        params = _llm_calls._prepare_model_params(
            model_parameters=model_parameters,
            call_time_params=call_time_params,
            response_model=None,
        )

        # Call-time should override
        assert params["temperature"] == 0.7
        assert params["top_p"] == 0.8

    def test_prepare_params_adds_response_format(self) -> None:
        """Test that response_model is added as response_format."""
        params = _llm_calls._prepare_model_params(
            model_parameters={},
            call_time_params={},
            response_model=SampleResponseModel,
        )

        assert params["response_format"] is SampleResponseModel

    def test_prepare_params_adds_project_name(self) -> None:
        """Test that project_name is added to opik metadata."""
        params = _llm_calls._prepare_model_params(
            model_parameters={},
            call_time_params={},
            project_name="test-project",
        )

        assert params["metadata"]["opik"]["project_name"] == "test-project"

    def test_prepare_params_adds_optimization_tags(self) -> None:
        """Test that optimization_id adds tags."""
        params = _llm_calls._prepare_model_params(
            model_parameters={},
            call_time_params={},
            optimization_id="opt-123",
        )

        assert "opt-123" in params["metadata"]["opik"]["tags"]
        assert "Prompt Optimization" in params["metadata"]["opik"]["tags"]


class TestCallModelSync:
    """Test synchronous call_model function."""

    def test_call_model_increments_counter(self) -> None:
        """Test that call_model increments LLM counter."""
        mock_response = MagicMock()
        mock_response.choices = [MagicMock()]
        mock_response.choices[0].message.content = "response"
        mock_response.choices[0].finish_reason = "stop"

        with patch("opik_optimizer._llm_calls._increment_llm_counter_if_in_optimizer") as mock_inc:
            with patch("opik_optimizer._llm_calls.track_completion") as mock_track:
                mock_track.return_value = lambda x: x
                with patch("litellm.completion", return_value=mock_response):
                    _llm_calls.call_model(
                        messages=[{"role": "user", "content": "test"}],
                        model="gpt-4o",
                    )
                    mock_inc.assert_called_once()

    def test_call_model_with_structured_output(self) -> None:
        """Test call_model with Pydantic response model."""
        mock_response = MagicMock()
        mock_response.choices = [MagicMock()]
        mock_response.choices[0].message.content = '{"name": "test", "score": 0.9}'
        mock_response.choices[0].finish_reason = "stop"

        with patch("opik_optimizer._llm_calls.track_completion") as mock_track:
            mock_completion = MagicMock(return_value=mock_response)
            mock_track.return_value = lambda x: mock_completion

            result = _llm_calls.call_model(
                messages=[{"role": "user", "content": "test"}],
                model="gpt-4o",
                response_model=SampleResponseModel,
            )

            assert isinstance(result, SampleResponseModel)
            assert result.name == "test"


class TestCallModelAsync:
    """Test asynchronous call_model_async function."""

    @pytest.mark.asyncio
    async def test_call_model_async_increments_counter(self) -> None:
        """Test that call_model_async increments LLM counter."""
        mock_response = MagicMock()
        mock_response.choices = [MagicMock()]
        mock_response.choices[0].message.content = "response"
        mock_response.choices[0].finish_reason = "stop"

        with patch("opik_optimizer._llm_calls._increment_llm_counter_if_in_optimizer") as mock_inc:
            with patch("opik_optimizer._llm_calls.track_completion") as mock_track:
                async_mock = AsyncMock(return_value=mock_response)
                mock_track.return_value = lambda x: async_mock

                await _llm_calls.call_model_async(
                    messages=[{"role": "user", "content": "test"}],
                    model="gpt-4o",
                )
                mock_inc.assert_called_once()

    @pytest.mark.asyncio
    async def test_call_model_async_with_structured_output(self) -> None:
        """Test call_model_async with Pydantic response model."""
        mock_response = MagicMock()
        mock_response.choices = [MagicMock()]
        mock_response.choices[0].message.content = '{"name": "test", "score": 0.9}'
        mock_response.choices[0].finish_reason = "stop"

        with patch("opik_optimizer._llm_calls.track_completion") as mock_track:
            async_mock = AsyncMock(return_value=mock_response)
            mock_track.return_value = lambda x: async_mock

            result = await _llm_calls.call_model_async(
                messages=[{"role": "user", "content": "test"}],
                model="gpt-4o",
                response_model=SampleResponseModel,
            )

            assert isinstance(result, SampleResponseModel)
            assert result.name == "test"

    @pytest.mark.asyncio
    async def test_call_model_async_passes_model_parameters(self) -> None:
        """Test that model_parameters are passed to acompletion."""
        mock_response = MagicMock()
        mock_response.choices = [MagicMock()]
        mock_response.choices[0].message.content = "response"
        mock_response.choices[0].finish_reason = "stop"

        captured_kwargs: dict[str, Any] = {}

        async def capture_call(**kwargs: Any) -> Any:
            captured_kwargs.update(kwargs)
            return mock_response

        with patch("opik_optimizer._llm_calls.track_completion") as mock_track:
            mock_track.return_value = lambda x: capture_call

            await _llm_calls.call_model_async(
                messages=[{"role": "user", "content": "test"}],
                model="gpt-4o",
                model_parameters={"temperature": 0.5},
                temperature=0.7,  # Call-time override
            )

            # Call-time should override model_parameters
            assert captured_kwargs["temperature"] == 0.7

    @pytest.mark.asyncio
    async def test_call_model_async_project_name_passed(self) -> None:
        """Test that project_name is passed to track_completion."""
        mock_response = MagicMock()
        mock_response.choices = [MagicMock()]
        mock_response.choices[0].message.content = "response"
        mock_response.choices[0].finish_reason = "stop"

        captured_project: str | None = None

        def capture_track(project_name: str | None = None):
            nonlocal captured_project
            captured_project = project_name
            return lambda x: AsyncMock(return_value=mock_response)

        with patch("opik_optimizer._llm_calls.track_completion", side_effect=capture_track):
            await _llm_calls.call_model_async(
                messages=[{"role": "user", "content": "test"}],
                model="gpt-4o",
                project_name="my-project",
            )

            assert captured_project == "my-project"


class TestStripProjectName:
    """Test _strip_project_name utility."""

    def test_strip_removes_project_name(self) -> None:
        """Test that project_name is removed from opik metadata."""
        params = {
            "model": "gpt-4o",
            "metadata": {
                "opik": {
                    "project_name": "test",
                    "tags": ["tag1"],
                }
            },
        }

        result = _llm_calls._strip_project_name(params)

        # Original unchanged
        assert "project_name" in params["metadata"]["opik"]

        # Result has project_name removed
        assert "project_name" not in result["metadata"]["opik"]
        assert result["metadata"]["opik"]["tags"] == ["tag1"]

    def test_strip_handles_missing_metadata(self) -> None:
        """Test handling of missing metadata."""
        params = {"model": "gpt-4o"}
        result = _llm_calls._strip_project_name(params)
        assert result == params

    def test_strip_handles_empty_opik(self) -> None:
        """Test that empty opik metadata is removed."""
        params = {
            "metadata": {
                "opik": {
                    "project_name": "test",
                }
            }
        }

        result = _llm_calls._strip_project_name(params)

        # Empty opik dict should be removed
        assert "opik" not in result["metadata"]


class TestCounterIncrement:
    """Test LLM counter increment from optimizer context."""

    def test_increment_llm_counter_walks_stack(self) -> None:
        """Test that counter increment walks call stack to find optimizer."""
        from opik_optimizer.base_optimizer import BaseOptimizer

        class MockOptimizer(BaseOptimizer):
            DEFAULT_PROMPTS: dict[str, str] = {}

            def optimize_prompt(self, *args: Any, **kwargs: Any) -> Any:
                pass

            def get_optimizer_metadata(self) -> dict[str, Any]:
                return {}

        optimizer = MockOptimizer(model="gpt-4o")
        initial_count = optimizer.llm_call_counter

        # Simulate a call from within optimizer context
        def inner_call() -> None:
            _llm_calls._increment_llm_counter_if_in_optimizer()

        # Need 'self' in locals for frame inspection
        self = optimizer
        inner_call()

        assert optimizer.llm_call_counter == initial_count + 1

    def test_increment_tool_counter_walks_stack(self) -> None:
        """Test that tool counter increment walks call stack."""
        from opik_optimizer.base_optimizer import BaseOptimizer

        class MockOptimizer(BaseOptimizer):
            DEFAULT_PROMPTS: dict[str, str] = {}

            def optimize_prompt(self, *args: Any, **kwargs: Any) -> Any:
                pass

            def get_optimizer_metadata(self) -> dict[str, Any]:
                return {}

        optimizer = MockOptimizer(model="gpt-4o")
        initial_count = optimizer.tool_call_counter

        self = optimizer
        _llm_calls._increment_tool_counter_if_in_optimizer()

        assert optimizer.tool_call_counter == initial_count + 1

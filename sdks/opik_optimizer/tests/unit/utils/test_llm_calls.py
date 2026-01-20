"""
Unit tests for opik_optimizer.core.llm_calls module.

Tests cover:
- _build_call_time_params: Parameter filtering and building
- _prepare_model_params: Parameter merging and Opik monitoring setup
- _parse_response: Response parsing with structured output and error handling
- StructuredOutputParsingError: Exception behavior
"""

from typing import Any, cast
from collections.abc import Callable
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from pydantic import BaseModel, ValidationError

from opik_optimizer.core.llm_calls import (
    _build_call_time_params,
    _prepare_model_params,
    _parse_response,
    StructuredOutputParsingError,
)
from opik_optimizer.core import llm_calls as _llm_calls
from opik_optimizer.base_optimizer import BaseOptimizer
from opik_optimizer.core.state import OptimizationContext
from tests.unit.test_helpers import make_mock_response


class TestBuildCallTimeParams:
    """Tests for _build_call_time_params function."""

    def test_returns_empty_dict_when_no_params_provided(self) -> None:
        """When no parameters are provided, should return an empty dict."""
        result = _build_call_time_params()
        assert result == {}

    def test_includes_only_non_none_parameters(self) -> None:
        """Only non-None parameters should be included in the result."""
        result = _build_call_time_params(
            temperature=0.7,
            max_tokens=None,
            top_p=0.9,
        )

        assert result == {"temperature": 0.7, "top_p": 0.9}
        assert "max_tokens" not in result

    def test_includes_all_parameters_when_all_provided(self) -> None:
        """All parameters should be included when all are provided."""
        result = _build_call_time_params(
            temperature=0.5,
            max_tokens=100,
            max_completion_tokens=200,
            top_p=0.8,
            presence_penalty=0.1,
            frequency_penalty=0.2,
            metadata={"key": "value"},
        )

        assert result == {
            "temperature": 0.5,
            "max_tokens": 100,
            "max_completion_tokens": 200,
            "top_p": 0.8,
            "presence_penalty": 0.1,
            "frequency_penalty": 0.2,
            "metadata": {"key": "value"},
        }

    def test_handles_zero_values_correctly(self) -> None:
        """Zero values should be included (they are not None)."""
        result = _build_call_time_params(
            temperature=0.0,
            presence_penalty=0.0,
        )

        assert result == {"temperature": 0.0, "presence_penalty": 0.0}

    def test_metadata_is_passed_through_unchanged(self) -> None:
        """Metadata dict should be passed through without modification."""
        metadata = {"custom_key": "custom_value", "nested": {"key": 1}}
        result = _build_call_time_params(metadata=metadata)

        assert result["metadata"] == metadata
        # Ensure it's the same reference (not copied)
        assert result["metadata"] is metadata


class TestPrepareModelParams:
    """Tests for _prepare_model_params function."""

    def test_merges_model_parameters_with_call_time_params(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """Call-time params should override model parameters."""
        # Mock the Opik monitoring to return the params unchanged
        monkeypatch.setattr(
            "opik_optimizer.core.llm_calls.opik_litellm_monitor.try_add_opik_monitoring_to_params",
            lambda x: x,
        )

        model_params = {"temperature": 0.5, "max_tokens": 100}
        call_time = {"temperature": 0.8}  # Override

        result = _prepare_model_params(model_params, call_time)

        assert result["temperature"] == 0.8  # Call-time wins
        assert result["max_tokens"] == 100  # Preserved from model_params

    def test_adds_reasoning_metadata_when_is_reasoning_true(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """When is_reasoning=True, should add opik_call_type metadata.

        Note: The mock returns params with an empty metadata dict. The actual
        _prepare_model_params function then adds opik_call_type AFTER calling
        the monitoring wrapper, so this test verifies that second step works.
        """
        # Mock returns params with empty metadata dict (simulating what the real
        # monitoring wrapper does - it ensures metadata exists)
        monkeypatch.setattr(
            "opik_optimizer.core.llm_calls.opik_litellm_monitor.try_add_opik_monitoring_to_params",
            lambda x: {**x, "metadata": x.get("metadata", {})},
        )

        result = _prepare_model_params(
            model_parameters={},
            call_time_params={},
            is_reasoning=True,
        )

        # _prepare_model_params adds opik_call_type AFTER calling the mock
        assert result["metadata"]["opik_call_type"] == "reasoning"

    def test_does_not_add_reasoning_metadata_when_is_reasoning_false(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """When is_reasoning=False, should not add opik_call_type metadata."""
        monkeypatch.setattr(
            "opik_optimizer.core.llm_calls.opik_litellm_monitor.try_add_opik_monitoring_to_params",
            lambda x: x,
        )

        result = _prepare_model_params(
            model_parameters={},
            call_time_params={},
            is_reasoning=False,
        )

        # metadata.opik_call_type should not exist
        assert result.get("metadata", {}).get("opik_call_type") is None

    def test_adds_project_name_to_opik_metadata(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """Project name should be added to metadata.opik."""
        monkeypatch.setattr(
            "opik_optimizer.core.llm_calls.opik_litellm_monitor.try_add_opik_monitoring_to_params",
            lambda x: x,
        )

        result = _prepare_model_params(
            model_parameters={},
            call_time_params={},
            project_name="my-project",
        )

        assert result["metadata"]["opik"]["project_name"] == "my-project"

    def test_adds_optimization_id_tags(self, monkeypatch: pytest.MonkeyPatch) -> None:
        """Optimization ID should add tags to metadata.opik."""
        monkeypatch.setattr(
            "opik_optimizer.core.llm_calls.opik_litellm_monitor.try_add_opik_monitoring_to_params",
            lambda x: x,
        )

        result = _prepare_model_params(
            model_parameters={},
            call_time_params={},
            optimization_id="opt-123",
        )

        assert "opt-123" in result["metadata"]["opik"]["tags"]
        assert "Prompt Optimization" in result["metadata"]["opik"]["tags"]

    def test_adds_response_format_when_response_model_provided(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """When response_model is provided, should add response_format."""
        monkeypatch.setattr(
            "opik_optimizer.core.llm_calls.opik_litellm_monitor.try_add_opik_monitoring_to_params",
            lambda x: x,
        )

        class MyModel(BaseModel):
            field: str

        result = _prepare_model_params(
            model_parameters={},
            call_time_params={},
            response_model=MyModel,
        )

        assert result["response_format"] is MyModel

    def test_preserves_existing_metadata(self, monkeypatch: pytest.MonkeyPatch) -> None:
        """Existing metadata should be preserved and extended, not replaced."""
        monkeypatch.setattr(
            "opik_optimizer.core.llm_calls.opik_litellm_monitor.try_add_opik_monitoring_to_params",
            lambda x: x,
        )

        result = _prepare_model_params(
            model_parameters={"metadata": {"existing_key": "value"}},
            call_time_params={},
            optimization_id="opt-456",
        )

        # Both existing and new metadata should be present
        assert result["metadata"]["existing_key"] == "value"
        assert "opt-456" in result["metadata"]["opik"]["tags"]


class TestParseResponse:
    """Tests for _parse_response function."""

    def test_returns_content_when_no_response_model(self) -> None:
        """When no response_model is provided, should return raw content."""
        mock_response = make_mock_response("Hello, world!")

        result = _parse_response(mock_response)

        assert result == "Hello, world!"

    def test_parses_structured_output_with_response_model(self) -> None:
        """When response_model is provided, should parse JSON into model."""

        class TestModel(BaseModel):
            name: str
            count: int

        mock_response = make_mock_response('{"name": "test", "count": 42}')

        result = _parse_response(mock_response, response_model=TestModel)

        assert isinstance(result, TestModel)
        assert result.name == "test"
        assert result.count == 42

    def test_parses_structured_output_from_parsed_object(self) -> None:
        """Should prefer message.parsed when available."""

        class TestModel(BaseModel):
            name: str
            count: int

        mock_response = make_mock_response(
            "not valid json",
            parsed={"name": "parsed", "count": 7},
        )

        result = _parse_response(mock_response, response_model=TestModel)

        assert isinstance(result, TestModel)
        assert result.name == "parsed"
        assert result.count == 7

    def test_parses_structured_output_from_parsed_model_instance(self) -> None:
        """Should return parsed model instance when it matches response_model."""

        class TestModel(BaseModel):
            name: str
            count: int

        parsed_model = TestModel(name="parsed", count=3)
        mock_response = make_mock_response("{}", parsed=parsed_model)

        result = _parse_response(mock_response, response_model=TestModel)

        assert result is parsed_model

    def test_parsed_object_invalid_falls_back_to_json(self) -> None:
        """Invalid parsed object should fall back to JSON parsing."""

        class TestModel(BaseModel):
            name: str
            count: int

        mock_response = make_mock_response(
            '{"name": "json", "count": 11}',
            parsed={"name": "missing_count"},
        )

        result = _parse_response(mock_response, response_model=TestModel)

        assert isinstance(result, TestModel)
        assert result.name == "json"
        assert result.count == 11

    def test_raises_bad_request_error_on_truncation_with_empty_content(self) -> None:
        """Should raise BadRequestError when content is empty and finish_reason indicates truncation."""
        from litellm.exceptions import BadRequestError

        mock_response = make_mock_response("", finish_reason="length")

        with pytest.raises(BadRequestError) as exc_info:
            _parse_response(mock_response)

        assert "max_tokens" in str(exc_info.value)

    def test_does_not_raise_on_truncation_with_non_empty_content(self) -> None:
        """Should NOT raise when finish_reason is 'length' but content is not empty."""
        mock_response = make_mock_response(
            "Some partial content", finish_reason="length"
        )

        result = _parse_response(mock_response)

        assert result == "Some partial content"

    def test_handles_max_tokens_finish_reason(self) -> None:
        """Should handle 'max_tokens' finish reason like 'length'."""
        from litellm.exceptions import BadRequestError

        mock_response = make_mock_response("   ", finish_reason="max_tokens")

        with pytest.raises(BadRequestError):
            _parse_response(mock_response)

    def test_handles_token_limit_finish_reason(self) -> None:
        """Should handle 'token limit' finish reason like 'length'."""
        from litellm.exceptions import BadRequestError

        mock_response = make_mock_response("", finish_reason="token limit")

        with pytest.raises(BadRequestError):
            _parse_response(mock_response)

    def test_raises_structured_output_parsing_error_on_invalid_json(self) -> None:
        """Should raise StructuredOutputParsingError when JSON is invalid."""

        class TestModel(BaseModel):
            field: str

        mock_response = make_mock_response("not valid json")

        with pytest.raises(StructuredOutputParsingError) as exc_info:
            _parse_response(mock_response, response_model=TestModel)

        assert exc_info.value.content == "not valid json"

    def test_raises_structured_output_parsing_error_on_schema_mismatch(self) -> None:
        """Should raise StructuredOutputParsingError when JSON doesn't match schema."""

        class TestModel(BaseModel):
            required_field: str

        mock_response = make_mock_response('{"wrong_field": "value"}')

        with pytest.raises(StructuredOutputParsingError) as exc_info:
            _parse_response(mock_response, response_model=TestModel)

        assert "required_field" in str(exc_info.value.content) or isinstance(
            exc_info.value.error, ValidationError
        )

    def test_fallback_parsing_with_python_repr(self) -> None:
        """Should attempt fallback parsing for Python-style dicts."""

        class TestModel(BaseModel):
            name: str

        # Python repr style (single quotes) - fallback should handle this
        mock_response = make_mock_response("{'name': 'test'}")

        result = _parse_response(mock_response, response_model=TestModel)

        assert isinstance(result, TestModel)
        assert result.name == "test"

    def test_handles_none_finish_reason(self) -> None:
        """Should handle when finish_reason attribute is missing or None."""
        mock_response = make_mock_response("Response content")
        # Don't set finish_reason at all (will return None via getattr default)
        del mock_response.choices[0].finish_reason

        result = _parse_response(mock_response)

        assert result == "Response content"

    def test_return_all_prefers_parsed_objects(self) -> None:
        """When return_all=True, should parse each choice via message.parsed."""

        class TestModel(BaseModel):
            name: str

        mock_response = MagicMock()
        mock_response.choices = [MagicMock(), MagicMock()]
        mock_response.choices[0].message.content = "invalid"
        mock_response.choices[0].message.parsed = {"name": "first"}
        mock_response.choices[1].message.content = "invalid"
        mock_response.choices[1].message.parsed = {"name": "second"}

        result = _parse_response(
            mock_response, response_model=TestModel, return_all=True
        )

        parsed_results = cast(list[TestModel], result)
        assert [item.name for item in parsed_results] == ["first", "second"]


class TestStructuredOutputModels:
    """Tests for structured output response models used by optimizers."""

    def test_mutation_response_wraps_messages_list(self) -> None:
        from opik_optimizer.algorithms.evolutionary_optimizer.types import (
            MutationResponse,
        )

        payload = [{"role": "system", "content": "s"}, {"role": "user", "content": "u"}]
        parsed = MutationResponse.model_validate(payload)

        assert len(parsed.messages) == 2
        first = parsed.messages[0]
        role = first.get("role") if isinstance(first, dict) else getattr(first, "role")
        assert role == "system"

    def test_mutation_response_accepts_messages_dict(self) -> None:
        from opik_optimizer.algorithms.evolutionary_optimizer.types import (
            MutationResponse,
        )

        payload = {
            "messages": [
                {"role": "system", "content": "s"},
                {"role": "user", "content": "u"},
            ]
        }
        parsed = MutationResponse.model_validate(payload)

        assert len(parsed.messages) == 2

    def test_mutation_response_wraps_single_message_dict(self) -> None:
        from opik_optimizer.algorithms.evolutionary_optimizer.types import (
            MutationResponse,
        )

        payload = {"role": "system", "content": "s"}
        parsed = MutationResponse.model_validate(payload)

        assert len(parsed.messages) == 1
        first = parsed.messages[0]
        role = first.get("role") if isinstance(first, dict) else getattr(first, "role")
        assert role == "system"

    def test_prompt_candidates_response_wraps_list(self) -> None:
        from opik_optimizer.algorithms.meta_prompt_optimizer.types import (
            PromptCandidatesResponse,
        )

        payload = [
            {
                "prompt": [
                    {"role": "system", "content": "s"},
                    {"role": "user", "content": "u"},
                ]
            }
        ]
        parsed = PromptCandidatesResponse.model_validate(payload)

        assert len(parsed.prompts) == 1
        first = parsed.prompts[0].prompt[0]
        role = first.get("role") if isinstance(first, dict) else getattr(first, "role")
        assert role == "system"

    def test_prompt_candidates_response_accepts_dict(self) -> None:
        from opik_optimizer.algorithms.meta_prompt_optimizer.types import (
            PromptCandidatesResponse,
        )

        payload = {
            "prompts": [
                {
                    "prompt": [
                        {"role": "system", "content": "s"},
                        {"role": "user", "content": "u"},
                    ]
                }
            ]
        }
        parsed = PromptCandidatesResponse.model_validate(payload)

        assert len(parsed.prompts) == 1

    def test_pattern_extraction_response_accepts_objects(self) -> None:
        from opik_optimizer.algorithms.meta_prompt_optimizer.types import (
            PatternExtractionResponse,
        )

        payload = [
            {"pattern": "Be concise", "example": "Short answers"},
            {"pattern": "Use citations"},
        ]
        parsed = PatternExtractionResponse.model_validate(payload)

        assert len(parsed.patterns) == 2
        first = parsed.patterns[0]
        pattern_text = first.pattern if hasattr(first, "pattern") else first
        assert pattern_text == "Be concise"

    def test_pattern_extraction_response_wraps_list(self) -> None:
        from opik_optimizer.algorithms.meta_prompt_optimizer.types import (
            PatternExtractionResponse,
        )

        payload = ["Pattern A", "Pattern B"]
        parsed = PatternExtractionResponse.model_validate(payload)

        assert parsed.patterns == ["Pattern A", "Pattern B"]


class TestStructuredOutputParsingError:
    """Tests for StructuredOutputParsingError exception."""

    def test_stores_content_and_error(self) -> None:
        """Exception should store both the content and the original error."""
        original_error = ValueError("Original error")
        exc = StructuredOutputParsingError(
            content="failed content",
            error=original_error,
        )

        assert exc.content == "failed content"
        assert exc.error is original_error

    def test_message_includes_content_and_error(self) -> None:
        """Exception message should include both content and error info."""
        original_error = ValueError("Parse failed")
        exc = StructuredOutputParsingError(
            content="{'bad': json}",
            error=original_error,
        )

        message = str(exc)
        assert "Parse failed" in message
        assert "{'bad': json}" in message

    def test_can_be_caught_as_exception(self) -> None:
        """Should be catchable as a regular Exception."""
        with pytest.raises(Exception):
            raise StructuredOutputParsingError(
                content="content",
                error=ValueError("error"),
            )


class TestEdgeCases:
    """Edge case tests for _llm_calls module."""

    def test_parse_response_handles_nested_pydantic_model(self) -> None:
        """Should correctly parse nested Pydantic models."""

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
        """Should correctly parse Pydantic models with list fields."""

        class ListModel(BaseModel):
            items: list[str]

        mock_response = make_mock_response('{"items": ["a", "b", "c"]}')

        result = _parse_response(mock_response, response_model=ListModel)

        assert isinstance(result, ListModel)
        assert result.items == ["a", "b", "c"]

    def test_parse_response_handles_unicode_content(self) -> None:
        """Should correctly handle Unicode content."""
        mock_response = make_mock_response("Hello, 世界! 🌍")

        result = _parse_response(mock_response)

        assert result == "Hello, 世界! 🌍"

    def test_build_call_time_params_with_empty_metadata(self) -> None:
        """Should include empty metadata dict if provided."""
        result = _build_call_time_params(metadata={})

        assert result == {"metadata": {}}

    def test_prepare_model_params_without_project_name_preserves_caller_settings(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """When project_name is None, existing opik.project_name should be preserved."""
        monkeypatch.setattr(
            "opik_optimizer.core.llm_calls.opik_litellm_monitor.try_add_opik_monitoring_to_params",
            lambda x: x,
        )

        model_params = {"metadata": {"opik": {"project_name": "existing-project"}}}

        result = _prepare_model_params(
            model_parameters=model_params,
            call_time_params={},
            project_name=None,  # Should not override
        )

        assert result["metadata"]["opik"]["project_name"] == "existing-project"


class SampleResponseModel(BaseModel):
    """Sample Pydantic model for testing structured output."""

    name: str
    score: float


class TestCallModelSync:
    """Test synchronous call_model function."""

    def test_call_model_increments_counter(self) -> None:
        """Test that call_model increments LLM counter."""
        mock_response = make_mock_response("response")

        with patch(
            "opik_optimizer.core.llm_calls._increment_llm_counter_if_in_optimizer"
        ) as mock_inc:
            with patch("opik_optimizer.core.llm_calls.track_completion") as mock_track:
                mock_track.return_value = lambda x: x
                with patch("litellm.completion", return_value=mock_response):
                    _llm_calls.call_model(
                        messages=[{"role": "user", "content": "test"}],
                        model="gpt-4o",
                    )
                    mock_inc.assert_called_once()

    def test_call_model_with_structured_output(self) -> None:
        """Test call_model with Pydantic response model."""
        mock_response = make_mock_response('{"name": "test", "score": 0.9}')

        with patch("opik_optimizer.core.llm_calls.track_completion") as mock_track:
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
        mock_response = make_mock_response("response")

        with patch(
            "opik_optimizer.core.llm_calls._increment_llm_counter_if_in_optimizer"
        ) as mock_inc:
            with patch("opik_optimizer.core.llm_calls.track_completion") as mock_track:
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
        mock_response = make_mock_response('{"name": "test", "score": 0.9}')

        with patch("opik_optimizer.core.llm_calls.track_completion") as mock_track:
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
        mock_response = make_mock_response("response")

        captured_kwargs: dict[str, Any] = {}

        async def capture_call(**kwargs: Any) -> Any:
            captured_kwargs.update(kwargs)
            return mock_response

        with patch("opik_optimizer.core.llm_calls.track_completion") as mock_track:
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
                messages=[{"role": "user", "content": "test"}],
                model="gpt-4o",
                project_name="my-project",
            )

            assert captured_project == "my-project"


class TestStripProjectName:
    """Test _strip_project_name utility."""

    def test_strip_removes_project_name(self) -> None:
        """Test that project_name is removed from opik metadata."""
        params: dict[str, Any] = {
            "model": "gpt-4o",
            "metadata": {
                "opik": {
                    "project_name": "test",
                    "tags": ["tag1"],
                }
            },
        }

        result: dict[str, Any] = _llm_calls._strip_project_name(params)

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

        # Simulate a call from within optimizer context
        def inner_call(self: Any) -> None:
            _llm_calls._increment_llm_counter_if_in_optimizer()

        inner_call(optimizer)

        assert optimizer.llm_call_counter == initial_count + 1

    def test_increment_llm_call_tools_counter_walks_stack(self) -> None:
        """Test that tool counter increment walks call stack."""

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

"""
Unit tests for opik_optimizer._llm_calls module.

Tests cover:
- _build_call_time_params: Parameter filtering and building
- _prepare_model_params: Parameter merging and Opik monitoring setup
- _parse_response: Response parsing with structured output and error handling
- StructuredOutputParsingError: Exception behavior
"""

import pytest
from unittest.mock import MagicMock
from pydantic import BaseModel, ValidationError

from opik_optimizer._llm_calls import (
    _build_call_time_params,
    _prepare_model_params,
    _parse_response,
    StructuredOutputParsingError,
)


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
            "opik_optimizer._llm_calls.opik_litellm_monitor.try_add_opik_monitoring_to_params",
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
            "opik_optimizer._llm_calls.opik_litellm_monitor.try_add_opik_monitoring_to_params",
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
            "opik_optimizer._llm_calls.opik_litellm_monitor.try_add_opik_monitoring_to_params",
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
            "opik_optimizer._llm_calls.opik_litellm_monitor.try_add_opik_monitoring_to_params",
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
            "opik_optimizer._llm_calls.opik_litellm_monitor.try_add_opik_monitoring_to_params",
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
            "opik_optimizer._llm_calls.opik_litellm_monitor.try_add_opik_monitoring_to_params",
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
            "opik_optimizer._llm_calls.opik_litellm_monitor.try_add_opik_monitoring_to_params",
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
        mock_response = MagicMock()
        mock_response.choices = [MagicMock()]
        mock_response.choices[0].message.content = "Hello, world!"
        mock_response.choices[0].finish_reason = "stop"

        result = _parse_response(mock_response)

        assert result == "Hello, world!"

    def test_parses_structured_output_with_response_model(self) -> None:
        """When response_model is provided, should parse JSON into model."""

        class TestModel(BaseModel):
            name: str
            count: int

        mock_response = MagicMock()
        mock_response.choices = [MagicMock()]
        mock_response.choices[0].message.content = '{"name": "test", "count": 42}'
        mock_response.choices[0].finish_reason = "stop"

        result = _parse_response(mock_response, response_model=TestModel)

        assert isinstance(result, TestModel)
        assert result.name == "test"
        assert result.count == 42

    def test_raises_bad_request_error_on_truncation_with_empty_content(self) -> None:
        """Should raise BadRequestError when content is empty and finish_reason indicates truncation."""
        from litellm.exceptions import BadRequestError

        mock_response = MagicMock()
        mock_response.choices = [MagicMock()]
        mock_response.choices[0].message.content = ""
        mock_response.choices[0].finish_reason = "length"
        mock_response.model = "gpt-4"

        with pytest.raises(BadRequestError) as exc_info:
            _parse_response(mock_response)

        assert "max_tokens" in str(exc_info.value)

    def test_does_not_raise_on_truncation_with_non_empty_content(self) -> None:
        """Should NOT raise when finish_reason is 'length' but content is not empty."""
        mock_response = MagicMock()
        mock_response.choices = [MagicMock()]
        mock_response.choices[0].message.content = "Some partial content"
        mock_response.choices[0].finish_reason = "length"

        result = _parse_response(mock_response)

        assert result == "Some partial content"

    def test_handles_max_tokens_finish_reason(self) -> None:
        """Should handle 'max_tokens' finish reason like 'length'."""
        from litellm.exceptions import BadRequestError

        mock_response = MagicMock()
        mock_response.choices = [MagicMock()]
        mock_response.choices[0].message.content = "   "  # Whitespace only
        mock_response.choices[0].finish_reason = "max_tokens"
        mock_response.model = "gpt-4"

        with pytest.raises(BadRequestError):
            _parse_response(mock_response)

    def test_handles_token_limit_finish_reason(self) -> None:
        """Should handle 'token limit' finish reason like 'length'."""
        from litellm.exceptions import BadRequestError

        mock_response = MagicMock()
        mock_response.choices = [MagicMock()]
        mock_response.choices[0].message.content = ""
        mock_response.choices[0].finish_reason = "token limit"
        mock_response.model = "gpt-4"

        with pytest.raises(BadRequestError):
            _parse_response(mock_response)

    def test_raises_structured_output_parsing_error_on_invalid_json(self) -> None:
        """Should raise StructuredOutputParsingError when JSON is invalid."""

        class TestModel(BaseModel):
            field: str

        mock_response = MagicMock()
        mock_response.choices = [MagicMock()]
        mock_response.choices[0].message.content = "not valid json"
        mock_response.choices[0].finish_reason = "stop"

        with pytest.raises(StructuredOutputParsingError) as exc_info:
            _parse_response(mock_response, response_model=TestModel)

        assert exc_info.value.content == "not valid json"

    def test_raises_structured_output_parsing_error_on_schema_mismatch(self) -> None:
        """Should raise StructuredOutputParsingError when JSON doesn't match schema."""

        class TestModel(BaseModel):
            required_field: str

        mock_response = MagicMock()
        mock_response.choices = [MagicMock()]
        mock_response.choices[0].message.content = '{"wrong_field": "value"}'
        mock_response.choices[0].finish_reason = "stop"

        with pytest.raises(StructuredOutputParsingError) as exc_info:
            _parse_response(mock_response, response_model=TestModel)

        assert "required_field" in str(exc_info.value.content) or isinstance(
            exc_info.value.error, ValidationError
        )

    def test_fallback_parsing_with_python_repr(self) -> None:
        """Should attempt fallback parsing for Python-style dicts."""

        class TestModel(BaseModel):
            name: str

        mock_response = MagicMock()
        mock_response.choices = [MagicMock()]
        # Python repr style (single quotes) - fallback should handle this
        mock_response.choices[0].message.content = "{'name': 'test'}"
        mock_response.choices[0].finish_reason = "stop"

        result = _parse_response(mock_response, response_model=TestModel)

        assert isinstance(result, TestModel)
        assert result.name == "test"

    def test_handles_none_finish_reason(self) -> None:
        """Should handle when finish_reason attribute is missing or None."""
        mock_response = MagicMock()
        mock_response.choices = [MagicMock()]
        mock_response.choices[0].message.content = "Response content"
        # Don't set finish_reason at all (will return None via getattr default)
        del mock_response.choices[0].finish_reason

        result = _parse_response(mock_response)

        assert result == "Response content"


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

        mock_response = MagicMock()
        mock_response.choices = [MagicMock()]
        mock_response.choices[
            0
        ].message.content = '{"inner": {"value": 42}, "name": "test"}'
        mock_response.choices[0].finish_reason = "stop"

        result = _parse_response(mock_response, response_model=Outer)

        assert isinstance(result, Outer)
        assert result.inner.value == 42
        assert result.name == "test"

    def test_parse_response_handles_list_in_pydantic_model(self) -> None:
        """Should correctly parse Pydantic models with list fields."""

        class ListModel(BaseModel):
            items: list[str]

        mock_response = MagicMock()
        mock_response.choices = [MagicMock()]
        mock_response.choices[0].message.content = '{"items": ["a", "b", "c"]}'
        mock_response.choices[0].finish_reason = "stop"

        result = _parse_response(mock_response, response_model=ListModel)

        assert isinstance(result, ListModel)
        assert result.items == ["a", "b", "c"]

    def test_parse_response_handles_unicode_content(self) -> None:
        """Should correctly handle Unicode content."""
        mock_response = MagicMock()
        mock_response.choices = [MagicMock()]
        mock_response.choices[0].message.content = "Hello, 世界! 🌍"
        mock_response.choices[0].finish_reason = "stop"

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
            "opik_optimizer._llm_calls.opik_litellm_monitor.try_add_opik_monitoring_to_params",
            lambda x: x,
        )

        model_params = {"metadata": {"opik": {"project_name": "existing-project"}}}

        result = _prepare_model_params(
            model_parameters=model_params,
            call_time_params={},
            project_name=None,  # Should not override
        )

        assert result["metadata"]["opik"]["project_name"] == "existing-project"

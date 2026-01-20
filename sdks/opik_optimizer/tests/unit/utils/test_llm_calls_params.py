"""
Unit tests for parameter-building helpers in opik_optimizer.core.llm_calls.
"""

from __future__ import annotations

from typing import Any

import pytest
from pydantic import BaseModel

from opik_optimizer.core.llm_calls import _build_call_time_params, _prepare_model_params


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
        """When is_reasoning=True, should add opik_call_type metadata."""
        monkeypatch.setattr(
            "opik_optimizer.core.llm_calls.opik_litellm_monitor.try_add_opik_monitoring_to_params",
            lambda x: {**x, "metadata": x.get("metadata", {})},
        )

        result = _prepare_model_params(
            model_parameters={},
            call_time_params={},
            is_reasoning=True,
        )

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

        assert result["metadata"]["existing_key"] == "value"
        assert "opt-456" in result["metadata"]["opik"]["tags"]


class TestPrepareModelParamsEdgeCases:
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
            project_name=None,
        )

        assert result["metadata"]["opik"]["project_name"] == "existing-project"


class TestBuildCallTimeParamsEdgeCases:
    def test_build_call_time_params_with_empty_metadata(self) -> None:
        """Should include empty metadata dict if provided."""
        result = _build_call_time_params(metadata={})
        assert result == {"metadata": {}}


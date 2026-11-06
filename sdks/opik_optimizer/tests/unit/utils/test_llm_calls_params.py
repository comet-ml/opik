"""
Unit tests for parameter-building helpers in opik_optimizer.core.llm_calls.
"""

from __future__ import annotations


import pytest

from opik_optimizer.core.llm_calls import _prepare_model_params


class TestPrepareModelParams:
    """Tests for _prepare_model_params function."""

    def test_adds_optimization_id_tags(self, monkeypatch: pytest.MonkeyPatch) -> None:
        """Optimization ID should add tags to metadata.opik."""
        result = _prepare_model_params(
            model_parameters={},
            call_time_params={},
            optimization_id="opt-123",
        )

        assert "opt-123" in result["metadata"]["opik"]["tags"]
        assert "Prompt Optimization" in result["metadata"]["opik"]["tags"]

    def test_preserves_existing_metadata(self, monkeypatch: pytest.MonkeyPatch) -> None:
        """Existing metadata should be preserved and extended, not replaced."""
        result = _prepare_model_params(
            model_parameters={"metadata": {"existing_key": "value"}},
            call_time_params={},
            optimization_id="opt-456",
        )

        assert result["metadata"]["existing_key"] == "value"
        assert "opt-456" in result["metadata"]["opik"]["tags"]

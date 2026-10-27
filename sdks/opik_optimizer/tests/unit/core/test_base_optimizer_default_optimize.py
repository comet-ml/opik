"""Unit tests for BaseOptimizer default optimize_prompt flow."""

# mypy: disable-error-code=no-untyped-def

from __future__ import annotations


import pytest

from opik_optimizer.base_optimizer import AlgorithmResult, BaseOptimizer
from opik_optimizer.core.state import OptimizationContext
from tests.unit.test_helpers import make_mock_dataset


class TestDefaultOptimizePrompt:
    """Tests for the default optimize_prompt implementation in BaseOptimizer."""

    @pytest.fixture
    def mock_metric(self):
        def metric(dataset_item, llm_output):
            _ = dataset_item, llm_output
            return 1.0

        metric.__name__ = "test_metric"
        return metric

    def test_history_fallback_when_optimizer_returns_empty(
        self,
        simple_chat_prompt,
        mock_opik_client,
        mock_metric,
        monkeypatch,
    ) -> None:
        """Base should emit a fallback history entry when optimizer returns none."""
        mock_opik_client()
        mock_ds = make_mock_dataset(
            [{"id": "1", "input": "test"}],
            name="test-dataset",
            dataset_id="ds-123",
        )

        class EmptyHistoryOptimizer(BaseOptimizer):
            def run_optimization(self, context: OptimizationContext):
                return AlgorithmResult(
                    best_prompts=context.prompts,
                    best_score=0.5,
                    history=[],
                    metadata={},
                )

            def get_config(self, context: OptimizationContext):
                return {"optimizer": "EmptyHistoryOptimizer"}

            def get_optimizer_metadata(self):
                return {}

        optimizer = EmptyHistoryOptimizer(model="gpt-4")
        monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **kwargs: 0.5)

        result = optimizer.optimize_prompt(
            prompt=simple_chat_prompt,
            dataset=mock_ds,
            metric=mock_metric,
            max_trials=1,
        )

        assert result.history

    def test_baseline_forwards_experiment_config(
        self,
        simple_chat_prompt,
        mock_opik_client,
        mock_metric,
        monkeypatch,
    ) -> None:
        """User-provided experiment_config should be present in baseline evaluation call."""
        mock_opik_client()
        mock_ds = make_mock_dataset(
            [{"id": "1", "input": "test"}],
            name="test-dataset",
            dataset_id="ds-123",
        )

        from typing import Any, cast

        captured: dict[str, Any] = {}

        class BaselineCaptureOptimizer(BaseOptimizer):
            def run_optimization(self, context: OptimizationContext) -> AlgorithmResult:
                # No further evaluations; we only care about baseline plumbing.
                return AlgorithmResult(
                    best_prompts=context.prompts,
                    best_score=context.baseline_score or 0.0,
                    history=[],
                    metadata={},
                )

            def get_config(self, context: OptimizationContext):
                _ = context
                return {"optimizer": "BaselineCaptureOptimizer"}

            def get_optimizer_metadata(self):
                return {}

        optimizer = BaselineCaptureOptimizer(model="gpt-4", perfect_score=0.95)

        def fake_evaluate_prompt(**kwargs):
            captured["experiment_config"] = kwargs.get("experiment_config")
            return 0.1

        monkeypatch.setattr(optimizer, "evaluate_prompt", fake_evaluate_prompt)

        optimizer.optimize_prompt(
            prompt=simple_chat_prompt,
            dataset=mock_ds,
            metric=mock_metric,
            max_trials=1,
            experiment_config={"test_key": "test_value"},
        )

        assert isinstance(captured.get("experiment_config"), dict)
        experiment_config = cast(dict[str, Any], captured["experiment_config"])
        assert experiment_config["test_key"] == "test_value"

    def test_subclass_without_get_config_runs_with_display(
        self,
        simple_chat_prompt,
        mock_opik_client,
        mock_metric,
        monkeypatch,
    ) -> None:
        """A minimal subclass (no get_config override) must not break the console display."""
        mock_opik_client()

        class MinimalOptimizer(BaseOptimizer):
            def run_optimization(self, context: OptimizationContext):
                return AlgorithmResult(
                    best_prompts=context.prompts,
                    best_score=context.baseline_score or 0.0,
                )

        optimizer = MinimalOptimizer(model="gpt-4", verbose=1)
        monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **kwargs: 0.5)

        result = optimizer.optimize_prompt(
            prompt=simple_chat_prompt,
            dataset=make_mock_dataset([{"id": "1", "input": "test"}]),
            metric=mock_metric,
        )

        assert result.score == 0.5

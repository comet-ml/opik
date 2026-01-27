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

    def test_early_stops_on_perfect_baseline(
        self,
        simple_chat_prompt,
        mock_opik_client,
        mock_metric,
        monkeypatch,
    ) -> None:
        """Should return early result when baseline score meets threshold."""
        mock_opik_client()
        mock_ds = make_mock_dataset(
            [{"id": "1", "input": "test"}],
            name="test-dataset",
            dataset_id="ds-123",
        )

        class DefaultOptimizer(BaseOptimizer):
            def run_optimization(self, context: OptimizationContext):
                raise AssertionError("Should not be called")

            def get_config(self, context: OptimizationContext):
                return {"optimizer": "DefaultOptimizer"}

            def get_optimizer_metadata(self):
                return {}

        optimizer = DefaultOptimizer(model="gpt-4", perfect_score=0.9)
        monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **kwargs: 0.95)

        result = optimizer.optimize_prompt(
            prompt=simple_chat_prompt,
            dataset=mock_ds,
            metric=mock_metric,
            max_trials=10,
        )

        assert result.details["stopped_early"] is True
        assert result.details["stop_reason"] == "baseline_score_met_threshold"

    def test_calls_run_optimization_when_baseline_below_threshold(
        self,
        simple_chat_prompt,
        mock_opik_client,
        mock_metric,
        monkeypatch,
    ) -> None:
        """Should call _run_optimization when baseline doesn't meet threshold."""
        mock_opik_client()
        mock_ds = make_mock_dataset(
            [{"id": "1", "input": "test"}],
            name="test-dataset",
            dataset_id="ds-123",
        )

        run_optimization_called: list[bool] = []

        class DefaultOptimizer(BaseOptimizer):
            def run_optimization(self, context: OptimizationContext):
                run_optimization_called.append(True)
                return AlgorithmResult(
                    best_prompts={"prompt": list(context.prompts.values())[0]},
                    best_score=0.8,
                    history=[],
                    metadata={},
                )

            def get_config(self, context: OptimizationContext):
                return {"optimizer": "DefaultOptimizer"}

            def get_optimizer_metadata(self):
                return {}

        optimizer = DefaultOptimizer(model="gpt-4", perfect_score=0.9)
        monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **kwargs: 0.5)

        result = optimizer.optimize_prompt(
            prompt=simple_chat_prompt,
            dataset=mock_ds,
            metric=mock_metric,
            max_trials=10,
        )

        assert len(run_optimization_called) == 1
        assert result.score == 0.8

    def test_early_stop_reports_at_least_one_trial(
        self,
        simple_chat_prompt,
        mock_opik_client,
        mock_metric,
        monkeypatch,
    ) -> None:
        """Early stop should report at least 1 trial/round completed (baseline evaluation)."""
        mock_opik_client()
        mock_ds = make_mock_dataset(
            [{"id": "1", "input": "test"}],
            name="test-dataset",
            dataset_id="ds-123",
        )

        class DefaultOptimizer(BaseOptimizer):
            def run_optimization(self, context: OptimizationContext):
                raise AssertionError("Should not be called")

            def get_config(self, context: OptimizationContext):
                return {"optimizer": "DefaultOptimizer"}

            def get_optimizer_metadata(self):
                return {}

        optimizer = DefaultOptimizer(model="gpt-4", perfect_score=0.9)
        monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **kwargs: 0.95)

        result = optimizer.optimize_prompt(
            prompt=simple_chat_prompt,
            dataset=mock_ds,
            metric=mock_metric,
            max_trials=10,
        )

        assert result.details["stopped_early"] is True
        assert result.details.get("trials_completed", 1) >= 1

    def test_early_stop_uses_optimizer_provided_counts(
        self,
        simple_chat_prompt,
        mock_opik_client,
        mock_metric,
        monkeypatch,
    ) -> None:
        """Early stop should use optimizer-provided trial/round counts if available."""
        mock_opik_client()
        mock_ds = make_mock_dataset(
            [{"id": "1", "input": "test"}],
            name="test-dataset",
            dataset_id="ds-123",
        )

        class CustomOptimizer(BaseOptimizer):
            def run_optimization(self, context: OptimizationContext):
                raise AssertionError("Should not be called")

            def get_config(self, context: OptimizationContext):
                return {"optimizer": "CustomOptimizer"}

            def get_optimizer_metadata(self):
                return {}

            def get_metadata(self, context: OptimizationContext):
                _ = context
                return {
                    "trials_completed": 3,
                    "custom_field": "test_value",
                }

        optimizer = CustomOptimizer(model="gpt-4", perfect_score=0.9)
        monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **kwargs: 0.95)

        result = optimizer.optimize_prompt(
            prompt=simple_chat_prompt,
            dataset=mock_ds,
            metric=mock_metric,
            max_trials=10,
        )

        assert result.details["stopped_early"] is True
        assert result.details["trials_completed"] == 3
        assert len(result.history) == 1
        assert result.details["custom_field"] == "test_value"

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

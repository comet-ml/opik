# mypy: disable-error-code="no-untyped-def, no-untyped-call"

"""
Unit tests for OptimizationContext and early stopping framework.

Tests cover:
- OptimizationContext fields (should_stop, finish_reason)
- evaluate() method setting should_stop flag
- Mid-optimization early stopping
"""

from __future__ import annotations

import pytest
from collections.abc import Callable
from typing import Any
from unittest.mock import MagicMock

from opik_optimizer.base_optimizer import BaseOptimizer
from opik_optimizer.core.state import OptimizationContext
from opik_optimizer.api_objects import chat_prompt
from opik_optimizer.core.state import AlgorithmResult
from tests.unit.test_helpers import make_mock_dataset
from tests.unit.fixtures.assertions import assert_baseline_early_stop
from tests.unit.fixtures import system_message


@pytest.fixture
def simple_chat_prompt() -> chat_prompt.ChatPrompt:
    """Create a simple chat prompt for testing."""
    return chat_prompt.ChatPrompt(
        name="test_prompt",
        messages=[system_message("You are a helpful assistant.")],
    )


@pytest.fixture
def single_item_dataset() -> Any:
    """Standard one-item dataset used across this module."""
    return make_mock_dataset(
        [{"id": "1", "input": "test"}],
        name="test-dataset",
        dataset_id="ds-123",
    )


@pytest.fixture
def make_metric() -> Callable[[float], Callable[..., Any]]:
    """Factory for metric functions returning a MagicMock with `.value`."""

    def _make(score: float) -> Callable[..., Any]:
        def metric_fn(item: Any, output: Any) -> Any:
            _ = item, output
            return MagicMock(value=score)

        metric_fn.__name__ = "test_metric"
        return metric_fn

    return _make


@pytest.fixture
def make_context() -> Any:
    """
    Factory for OptimizationContext to reduce boilerplate in field-level tests.

    Most unit tests only care about a couple fields; keep a stable baseline
    and override per-test as needed.
    """

    def _make(**overrides: Any) -> OptimizationContext:
        defaults: dict[str, Any] = {
            "prompts": {},
            "initial_prompts": {},
            "is_single_prompt_optimization": True,
            "dataset": MagicMock(),
            "evaluation_dataset": MagicMock(),
            "validation_dataset": None,
            "metric": MagicMock(),
            "agent": MagicMock(),
            "optimization": None,
            "optimization_id": None,
            "experiment_config": None,
            "n_samples": None,
            "max_trials": 10,
            "project_name": "Test",
        }
        defaults.update(overrides)
        return OptimizationContext(**defaults)

    return _make


class SimpleOptimizer(BaseOptimizer):
    """Simple optimizer for testing that uses evaluate() and checks should_stop."""

    def __init__(self, **kwargs):
        super().__init__(model="gpt-4", **kwargs)
        self.evaluation_count = 0
        self.stopped_early = False
        self._trials_completed = 0
        self._rounds_completed = 0

    def run_optimization(self, context: OptimizationContext):
        """Run optimization with early stopping support."""
        # Initialize tracking
        self._trials_completed = 0
        self._rounds_completed = 0

        best_score = context.baseline_score or 0.0
        best_prompt = context.prompts

        # Simulate multiple rounds of optimization
        for round_num in range(5):
            self._rounds_completed = round_num + 1

            # Check should_stop at the start of each round
            if context.should_stop:
                self.stopped_early = True
                break

            round_handle = self.pre_round(context)

            # Evaluate a candidate prompt
            score = self.evaluate_prompt(
                prompt=best_prompt,
                dataset=context.evaluation_dataset,
                metric=context.metric,
                agent=context.agent,
                n_samples=context.n_samples,
                verbose=0,
            )

            self._trials_completed += 1
            self.evaluation_count += 1

            # Update context counters
            context.trials_completed = self._trials_completed

            self.post_trial(
                context,
                best_prompt,
                score=score,
                round_handle=round_handle,
            )
            self.post_round(round_handle)

            # Check for perfect score early stop
            if self.skip_perfect_score and score >= self.perfect_score:
                context.should_stop = True
                context.finish_reason = "perfect_score"

            if score > best_score:
                best_score = score

        # Set finish_reason if not already set
        if context.finish_reason is None:
            context.finish_reason = "completed"

        history_state = self.get_history_entries()
        return AlgorithmResult(
            best_prompts=best_prompt,
            best_score=best_score,
            history=history_state,
            metadata={
                "trials_completed": self._trials_completed,
                "stopped_early": self.stopped_early,
                "finish_reason": context.finish_reason,
                "initial_prompt": context.initial_prompts,
                "initial_score": context.baseline_score,
                "metric_name": context.metric.__name__,
            },
        )

    def get_config(self, context: OptimizationContext) -> dict[str, Any]:
        return {"optimizer": "SimpleOptimizer"}

    def get_metadata(self, context: OptimizationContext) -> dict[str, Any]:
        return {
            "trials_completed": self._trials_completed,
        }


class TestOptimizationContextFields:
    """Tests for OptimizationContext fields."""

    def test_context_has_should_stop_field(self, make_context: Any) -> None:
        """OptimizationContext should have should_stop field."""
        context = make_context()
        assert hasattr(context, "should_stop")
        assert context.should_stop is False

    def test_context_has_finish_reason_field(self, make_context: Any) -> None:
        """OptimizationContext should have finish_reason field."""
        context = make_context()
        assert hasattr(context, "finish_reason")
        assert context.finish_reason is None

    def test_context_has_trials_completed_field(self, make_context: Any) -> None:
        """OptimizationContext should have trials_completed field."""
        context = make_context()
        assert hasattr(context, "trials_completed")
        assert context.trials_completed == 0

    def test_context_has_current_best_score_field(self, make_context: Any) -> None:
        """OptimizationContext should have current_best_score field."""
        context = make_context()
        assert hasattr(context, "current_best_score")
        assert context.current_best_score is None


class TestMidOptimizationEarlyStop:
    """Tests for mid-optimization early stopping."""

    def test_optimizer_stops_on_perfect_score_mid_optimization(
        self,
        simple_chat_prompt,
        make_metric,
        mock_opik_client,
        single_item_dataset,
        monkeypatch,
    ):
        """Optimizer should stop when perfect score is reached mid-optimization."""
        # Use the centralized mock_opik_client fixture
        mock_opik_client()
        mock_ds = single_item_dataset

        # Create optimizer with low perfect_score threshold
        optimizer = SimpleOptimizer(perfect_score=0.75, skip_perfect_score=True)

        # Mock evaluate_prompt to return increasing scores
        scores = [0.5, 0.6, 0.8, 0.9, 1.0]  # Third score (0.8) exceeds threshold
        call_count = [0]

        def mock_evaluate(**kwargs):
            idx = min(call_count[0], len(scores) - 1)
            call_count[0] += 1
            return scores[idx]

        monkeypatch.setattr(optimizer, "evaluate_prompt", mock_evaluate)

        result = optimizer.optimize_prompt(
            prompt=simple_chat_prompt,
            dataset=mock_ds,
            metric=make_metric(0.8),
            max_trials=10,
        )

        # Should have stopped early after finding perfect score
        # Baseline (1) + 3 evaluations in loop = 4 total evaluations
        # but optimizer stops after 3rd evaluation (score 0.8 >= 0.75)
        assert optimizer.stopped_early is True
        assert result.details.get("finish_reason") == "perfect_score"
        # Should have completed at least 1 trial before stopping
        assert result.details.get("trials_completed", 0) >= 1

    def test_optimizer_completes_all_rounds_when_no_early_stop(
        self,
        simple_chat_prompt,
        make_metric,
        mock_opik_client,
        single_item_dataset,
        monkeypatch,
    ):
        """Optimizer should complete all rounds when no early stop condition is met."""
        mock_opik_client()
        mock_ds = single_item_dataset

        # Create optimizer with high perfect_score threshold (won't be reached)
        optimizer = SimpleOptimizer(perfect_score=0.99, skip_perfect_score=True)

        # Mock evaluate_prompt to return low scores
        monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **kwargs: 0.5)

        result = optimizer.optimize_prompt(
            prompt=simple_chat_prompt,
            dataset=mock_ds,
            metric=make_metric(0.8),
            max_trials=10,
        )

        # Should have completed all 5 rounds
        assert optimizer.stopped_early is False
        assert result.details.get("finish_reason") == "completed"
        assert len(result.history) == 5
        assert result.details.get("trials_completed") == 5

    def test_finish_reason_is_set_on_completion(
        self,
        simple_chat_prompt,
        make_metric,
        mock_opik_client,
        single_item_dataset,
        monkeypatch,
    ):
        """finish_reason should be set to 'completed' on normal completion."""
        mock_opik_client()
        mock_ds = single_item_dataset

        optimizer = SimpleOptimizer(perfect_score=0.99, skip_perfect_score=False)
        monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **kwargs: 0.5)

        result = optimizer.optimize_prompt(
            prompt=simple_chat_prompt,
            dataset=mock_ds,
            metric=make_metric(0.8),
            max_trials=10,
        )

        assert result.details.get("finish_reason") == "completed"


class TestBaselineEarlyStop:
    """Tests for baseline-level early stopping."""

    def test_baseline_early_stop_sets_stop_reason(
        self,
        simple_chat_prompt,
        make_metric,
        mock_opik_client,
        single_item_dataset,
        monkeypatch,
    ):
        """Early stop at baseline should set stop_reason to 'baseline_score_met_threshold'."""
        # Use the centralized mock_opik_client fixture
        mock_opik_client()
        mock_ds = single_item_dataset

        optimizer = SimpleOptimizer(perfect_score=0.9, skip_perfect_score=True)
        # Baseline score of 0.95 exceeds perfect_score of 0.9
        monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **kwargs: 0.95)

        result = optimizer.optimize_prompt(
            prompt=simple_chat_prompt,
            dataset=mock_ds,
            metric=make_metric(0.95),
            max_trials=10,
        )

        assert_baseline_early_stop(result, perfect_score=0.9)

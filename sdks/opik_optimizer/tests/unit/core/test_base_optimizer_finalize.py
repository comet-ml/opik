"""Unit tests for BaseOptimizer finalize/result building behaviors."""

from __future__ import annotations

from unittest.mock import MagicMock

import pytest

from opik_optimizer import ChatPrompt
from opik_optimizer.core.state import OptimizationContext
from tests.unit.fixtures.base_optimizer_test_helpers import ConcreteOptimizer
from tests.unit.test_helpers import make_mock_dataset, make_optimization_context


class TestFinalizeOptimization:
    """Tests for _finalize_optimization method."""

    @pytest.fixture
    def optimizer(self) -> ConcreteOptimizer:
        return ConcreteOptimizer(model="gpt-4")

    def test_updates_optimization_status(self, optimizer) -> None:
        """Should update optimization status when optimization exists."""
        mock_optimization = MagicMock()
        context = make_optimization_context(
            ChatPrompt(name="test", system="test", user="test"),
            optimization=mock_optimization,
            optimization_id="opt-123",
        )

        optimizer._finalize_optimization(context, status="completed")

        mock_optimization.update.assert_called_with(status="completed")

    def test_handles_none_optimization(self, optimizer) -> None:
        """Should not raise when optimization is None."""
        context = make_optimization_context(
            ChatPrompt(name="test", system="test", user="test"),
        )

        optimizer._finalize_optimization(context, status="completed")


class TestFinalizeFinishReason:
    """Tests for _finalize_finish_reason method."""

    @pytest.fixture
    def optimizer(self) -> ConcreteOptimizer:
        return ConcreteOptimizer(model="gpt-4")

    @pytest.fixture
    def base_context(self) -> OptimizationContext:
        """Create a minimal context for testing."""
        return make_optimization_context(
            ChatPrompt(name="test", system="test", user="test"),
        )

    @pytest.mark.parametrize(
        "trials_completed,max_trials,initial_finish_reason,expected",
        [
            (10, 10, None, "max_trials"),
            (15, 10, None, "max_trials"),
            (5, 10, None, "completed"),
            (0, 10, None, "completed"),
            (0, 0, None, "max_trials"),
            (10, 10, "perfect_score", "perfect_score"),
        ],
    )
    def test_finish_reason_logic(
        self,
        optimizer: ConcreteOptimizer,
        base_context: OptimizationContext,
        trials_completed: int,
        max_trials: int,
        initial_finish_reason: str | None,
        expected: str,
    ) -> None:
        base_context.trials_completed = trials_completed
        base_context.max_trials = max_trials
        base_context.finish_reason = initial_finish_reason  # type: ignore[assignment]

        optimizer._finalize_finish_reason(base_context)

        assert base_context.finish_reason == expected

    def test_preserves_early_stop_reasons(self, optimizer, base_context) -> None:
        """Various early stop reasons should be preserved."""
        for early_reason in [
            "perfect_score",
            "no_improvement",
            "convergence",
            "error",
            "cancelled",
        ]:
            base_context.trials_completed = 10
            base_context.finish_reason = early_reason

            optimizer._finalize_finish_reason(base_context)

            assert base_context.finish_reason == early_reason

    def test_zero_trials_sets_completed(self, optimizer, base_context) -> None:
        """Edge case: 0 trials completed should set 'completed'."""
        base_context.trials_completed = 0
        base_context.max_trials = 10
        base_context.finish_reason = None

        optimizer._finalize_finish_reason(base_context)

        assert base_context.finish_reason == "completed"

    def test_zero_max_trials_edge_case(self, optimizer, base_context) -> None:
        """Edge case: max_trials=0, trials_completed=0 should set 'max_trials'."""
        base_context.trials_completed = 0
        base_context.max_trials = 0
        base_context.finish_reason = None

        optimizer._finalize_finish_reason(base_context)

        assert base_context.finish_reason == "max_trials"


class TestBuildFinalResultStoppedEarly:
    """Tests for stopped_early and stop_reason in _build_final_result."""

    @pytest.fixture
    def optimizer(self) -> ConcreteOptimizer:
        return ConcreteOptimizer(model="gpt-4")

    @pytest.fixture
    def base_context(self, simple_chat_prompt) -> OptimizationContext:
        """Create a context with required fields for _build_final_result."""
        dataset = make_mock_dataset(dataset_id="ds-123")
        metric = MagicMock(__name__="test_metric")
        return make_optimization_context(
            simple_chat_prompt,
            dataset=dataset,
            metric=metric,
            optimization_id="opt-123",
            baseline_score=0.5,
        )

    @pytest.mark.parametrize(
        "finish_reason,trials_completed,expected_stopped,expected_reason",
        [
            ("max_trials", 10, True, "max_trials"),
            ("completed", 5, False, "completed"),
            ("perfect_score", 3, True, "perfect_score"),
            ("no_improvement", 7, True, "no_improvement"),
        ],
    )
    def test_stopped_early_logic(
        self,
        optimizer: ConcreteOptimizer,
        base_context: OptimizationContext,
        simple_chat_prompt: ChatPrompt,
        finish_reason: str,
        trials_completed: int,
        expected_stopped: bool,
        expected_reason: str,
    ) -> None:
        from opik_optimizer.core.state import AlgorithmResult

        base_context.finish_reason = finish_reason  # type: ignore[assignment]
        base_context.trials_completed = trials_completed

        algorithm_result = AlgorithmResult(
            best_prompts={"main": simple_chat_prompt},
            best_score=0.8,
            history=[],
            metadata={},
        )

        result = optimizer._build_final_result(algorithm_result, base_context)

        assert result.details["stopped_early"] is expected_stopped
        assert result.details["stop_reason"] == expected_reason
        if finish_reason == "max_trials":
            assert result.details["finish_reason"] == expected_reason


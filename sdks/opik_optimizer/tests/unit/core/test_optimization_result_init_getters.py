"""Tests for OptimizationResult initialization + basic getters."""

from __future__ import annotations

from opik_optimizer import ChatPrompt
from opik_optimizer.core.results import OptimizationResult


class TestOptimizationResultInitialization:
    """Tests for OptimizationResult initialization."""

    def test_details_counters_default_from_history(self) -> None:
        result = OptimizationResult(
            prompt=ChatPrompt(system="Test", user="Query"),
            score=0.5,
            metric_name="accuracy",
            details={"iterations_completed": 3, "trials_used": 4},
        )
        assert len(result.history) == 0
        assert result.details.get("trials_completed") == 0

    def test_trials_completed_from_nested_history(self) -> None:
        result = OptimizationResult(
            prompt=ChatPrompt(system="Test", user="Query"),
            score=0.5,
            metric_name="accuracy",
            history=[
                {"round": 0, "trials": [{"trial_index": 0}, {"trial_index": 1}]},
                {"round": 1, "trials": [{"trial_index": 2}]},
            ],
        )
        assert len(result.history) == 2
        assert result.details.get("trials_completed") == 3

    def test_stop_reason_details_are_populated(self) -> None:
        result = OptimizationResult(
            prompt=ChatPrompt(system="Test", user="Query"),
            score=0.25,
            metric_name="accuracy",
            details={"stop_reason": "error", "error": "boom"},
        )
        stop_details = result.details.get("stop_reason_details") or {}
        assert stop_details.get("best_score") == 0.25
        assert stop_details.get("error") == (
            "An error occurred during optimization; see internal logs for details."
        )

    def test_creates_with_dict_of_prompts(self) -> None:
        prompts = {
            "main": ChatPrompt(system="Main", user="Query"),
            "helper": ChatPrompt(system="Helper", user="Task"),
        }
        result = OptimizationResult(
            prompt=prompts,
            score=0.9,
            metric_name="accuracy",
        )
        assert isinstance(result.prompt, dict)
        assert "main" in result.prompt

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


class TestOptimizationResultGetters:
    """Tests for OptimizationResult getter methods."""

    def test_get_run_link_returns_url(self) -> None:
        result = OptimizationResult(
            prompt=ChatPrompt(system="Test", user="Query"),
            score=0.85,
            metric_name="accuracy",
            optimization_id="opt-123",
            dataset_id="ds-456",
        )
        link = result.get_run_link()
        assert isinstance(link, str)

    def test_get_optimized_model_kwargs_returns_dict(self) -> None:
        result = OptimizationResult(
            prompt=ChatPrompt(system="Test", user="Query"),
            score=0.85,
            metric_name="accuracy",
            details={"optimized_model_kwargs": {"temperature": 0.7, "top_p": 0.9}},
        )
        kwargs = result.get_optimized_model_kwargs()
        assert kwargs == {"temperature": 0.7, "top_p": 0.9}

    def test_get_optimized_model_kwargs_returns_empty_when_missing(self) -> None:
        result = OptimizationResult(
            prompt=ChatPrompt(system="Test", user="Query"),
            score=0.85,
            metric_name="accuracy",
        )
        kwargs = result.get_optimized_model_kwargs()
        assert kwargs == {}

    def test_get_optimized_model_returns_model_name(self) -> None:
        result = OptimizationResult(
            prompt=ChatPrompt(system="Test", user="Query"),
            score=0.85,
            metric_name="accuracy",
            details={"optimized_model": "gpt-4-turbo"},
        )
        model = result.get_optimized_model()
        assert model == "gpt-4-turbo"

    def test_get_optimized_model_returns_none_when_missing(self) -> None:
        result = OptimizationResult(
            prompt=ChatPrompt(system="Test", user="Query"),
            score=0.85,
            metric_name="accuracy",
        )
        model = result.get_optimized_model()
        assert model is None

    def test_get_optimized_parameters_returns_params(self) -> None:
        result = OptimizationResult(
            prompt=ChatPrompt(system="Test", user="Query"),
            score=0.85,
            metric_name="accuracy",
            details={"optimized_parameters": {"temperature": 0.5, "max_tokens": 1000}},
        )
        params = result.get_optimized_parameters()
        assert params == {"temperature": 0.5, "max_tokens": 1000}

    def test_get_optimized_parameters_returns_empty_when_missing(self) -> None:
        result = OptimizationResult(
            prompt=ChatPrompt(system="Test", user="Query"),
            score=0.85,
            metric_name="accuracy",
        )
        params = result.get_optimized_parameters()
        assert params == {}

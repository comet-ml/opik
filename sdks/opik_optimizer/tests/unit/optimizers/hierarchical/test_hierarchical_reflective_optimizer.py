# mypy: disable-error-code=no-untyped-def

from collections.abc import Callable
from typing import Any
from unittest.mock import MagicMock

import pytest

from opik import Dataset
from opik_optimizer import (
    ChatPrompt,
    HierarchicalReflectiveOptimizer,
    OptimizationResult,
)
from opik_optimizer.algorithms.hierarchical_reflective_optimizer.types import (
    FailureMode,
    HierarchicalRootCauseAnalysis,
)


def _metric(dataset_item: dict[str, Any], llm_output: str) -> float:
    return 1.0


from tests.unit.test_helpers import make_mock_dataset


def _make_dataset() -> MagicMock:
    return make_mock_dataset(
        [{"id": "1", "question": "Q1", "answer": "A1"}],
        name="test-dataset",
        dataset_id="dataset-123",
    )


def _mock_experiment_result(score: float) -> MagicMock:
    score_result = MagicMock(value=score)
    test_result = MagicMock(score_results=[score_result])
    return MagicMock(test_results=[test_result])


class TestHierarchicalReflectiveOptimizerInit:
    def test_initialization_with_defaults(self) -> None:
        optimizer = HierarchicalReflectiveOptimizer(model="openai/gpt-4o")
        assert optimizer.model == "openai/gpt-4o"
        assert optimizer.n_threads == 12
        assert optimizer.max_parallel_batches == 5
        assert optimizer.batch_size == 25
        assert optimizer.verbose == 1
        assert optimizer.seed == 42

    def test_initialization_with_custom_params(self) -> None:
        optimizer = HierarchicalReflectiveOptimizer(
            model="openai/gpt-4o-mini",
            n_threads=8,
            max_parallel_batches=3,
            batch_size=10,
            verbose=0,
            seed=123,
        )
        assert optimizer.model == "openai/gpt-4o-mini"
        assert optimizer.n_threads == 8
        assert optimizer.max_parallel_batches == 3
        assert optimizer.batch_size == 10
        assert optimizer.verbose == 0
        assert optimizer.seed == 123

    def test_get_optimizer_metadata(self) -> None:
        optimizer = HierarchicalReflectiveOptimizer(
            model="openai/gpt-4o",
            n_threads=10,
            max_parallel_batches=4,
        )
        metadata = optimizer.get_optimizer_metadata()
        assert metadata["model"] == "openai/gpt-4o"
        assert metadata["n_threads"] == 10
        assert metadata["max_parallel_batches"] == 4
        assert metadata["seed"] == 42
        assert metadata["verbose"] == 1

    def test_counter_reset(self) -> None:
        optimizer = HierarchicalReflectiveOptimizer()
        optimizer._increment_llm_counter()
        optimizer._increment_llm_call_tools_counter()
        assert optimizer.llm_call_counter > 0
        assert optimizer.llm_call_tools_counter > 0
        optimizer._reset_counters()
        assert optimizer.llm_call_counter == 0
        assert optimizer.llm_call_tools_counter == 0


class TestHierarchicalReflectiveOptimizerOptimizePrompt:
    def test_single_prompt_returns_chat_prompt(
        self,
        mock_optimization_context,
        monkeypatch,
    ) -> None:
        mock_optimization_context()

        optimizer = HierarchicalReflectiveOptimizer(
            model="gpt-4o-mini", verbose=0, seed=42
        )
        prompt = ChatPrompt(system="Test", user="{question}")
        dataset = _make_dataset()

        eval_scores = iter([0.5, 0.6, 0.7, 0.85])

        def mock_evaluate(**kwargs):
            score = next(eval_scores, 0.85)
            if kwargs.get("return_evaluation_result"):
                return _mock_experiment_result(score)
            return score

        monkeypatch.setattr(optimizer, "evaluate_prompt", mock_evaluate)

        analysis = HierarchicalRootCauseAnalysis(
            total_test_cases=5,
            num_batches=1,
            unified_failure_modes=[
                FailureMode(
                    name="Test", description="Test failure", root_cause="Test cause"
                )
            ],
            synthesis_notes="Test",
        )
        monkeypatch.setattr(
            optimizer,
            "_hierarchical_root_cause_analysis",
            lambda evaluation_result: analysis,
        )

        def mock_generate_and_evaluate(**kwargs):
            prompts = kwargs.get("best_prompts", {})
            improved = {name: p.copy() for name, p in prompts.items()}
            return improved, 0.85, _mock_experiment_result(0.85)

        monkeypatch.setattr(
            optimizer, "_generate_and_evaluate_improvement", mock_generate_and_evaluate
        )

        result = optimizer.optimize_prompt(
            prompt=prompt,
            dataset=dataset,
            metric=_metric,
            max_trials=2,
            n_samples=2,
        )

        assert isinstance(result, OptimizationResult)
        assert isinstance(result.prompt, ChatPrompt)

    def test_dict_prompt_returns_dict(
        self,
        mock_optimization_context,
        monkeypatch,
    ) -> None:
        mock_optimization_context()

        optimizer = HierarchicalReflectiveOptimizer(
            model="gpt-4o-mini", verbose=0, seed=42
        )
        prompts = {
            "main": ChatPrompt(name="main", system="Main", user="{question}"),
            "secondary": ChatPrompt(
                name="secondary", system="Secondary", user="{input}"
            ),
        }
        dataset = _make_dataset()

        eval_scores = iter([0.5, 0.6, 0.7, 0.85])

        def mock_evaluate(**kwargs):
            score = next(eval_scores, 0.85)
            if kwargs.get("return_evaluation_result"):
                return _mock_experiment_result(score)
            return score

        monkeypatch.setattr(optimizer, "evaluate_prompt", mock_evaluate)

        analysis = HierarchicalRootCauseAnalysis(
            total_test_cases=5,
            num_batches=1,
            unified_failure_modes=[
                FailureMode(
                    name="Test", description="Test failure", root_cause="Test cause"
                )
            ],
            synthesis_notes="Test",
        )
        monkeypatch.setattr(
            optimizer,
            "_hierarchical_root_cause_analysis",
            lambda evaluation_result: analysis,
        )

        def mock_generate_and_evaluate(**kwargs):
            prompts_arg = kwargs.get("best_prompts", {})
            improved = {name: p.copy() for name, p in prompts_arg.items()}
            return improved, 0.85, _mock_experiment_result(0.85)

        monkeypatch.setattr(
            optimizer, "_generate_and_evaluate_improvement", mock_generate_and_evaluate
        )

        result = optimizer.optimize_prompt(
            prompt=prompts,
            dataset=dataset,
            metric=_metric,
            max_trials=2,
            n_samples=2,
        )

        assert isinstance(result, OptimizationResult)
        assert isinstance(result.prompt, dict)

    def test_invalid_prompt_raises_error(
        self,
        mock_optimization_context,
    ) -> None:
        mock_optimization_context()
        optimizer = HierarchicalReflectiveOptimizer(
            model="gpt-4o-mini", verbose=0, seed=42
        )
        dataset = _make_dataset()

        with pytest.raises((ValueError, TypeError)):
            optimizer.optimize_prompt(
                prompt="invalid string",  # type: ignore[arg-type]
                dataset=dataset,
                metric=_metric,
                max_trials=1,
            )


class TestHierarchicalReflectiveOptimizerEarlyStop:
    def test_skips_on_perfect_score(
        self,
        mock_opik_client: Callable[..., MagicMock],
        monkeypatch: pytest.MonkeyPatch,
    ) -> None:
        mock_opik_client()
        dataset = _make_dataset()
        optimizer = HierarchicalReflectiveOptimizer(model="gpt-4o", perfect_score=0.95)

        # Mock the base class's evaluate_prompt for baseline computation (returns float)
        monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **kwargs: 0.96)

        prompt = ChatPrompt(system="baseline", user="{question}")
        result = optimizer.optimize_prompt(
            prompt=prompt,
            dataset=dataset,
            metric=_metric,
            max_trials=1,
        )

        assert result.details["stopped_early"] is True
        assert result.details["stop_reason"] == "baseline_score_met_threshold"
        assert result.details["perfect_score"] == 0.95
        assert result.initial_score == result.score


class TestHierarchicalReflectiveOptimizerCounters:
    def test_respects_max_trials_and_records_history(
        self,
        mock_optimization_context,
        monkeypatch: pytest.MonkeyPatch,
    ) -> None:
        mock_optimization_context()
        dataset = _make_dataset()
        optimizer = HierarchicalReflectiveOptimizer(model="gpt-4o-mini", verbose=0)
        prompt = ChatPrompt(system="Test", user="{question}")

        monkeypatch.setattr(
            optimizer,
            "evaluate_prompt",
            lambda **kwargs: _mock_experiment_result(0.1)
            if kwargs.get("return_evaluation_result")
            else 0.1,
        )

        analysis = HierarchicalRootCauseAnalysis(
            total_test_cases=1,
            num_batches=1,
            unified_failure_modes=[
                FailureMode(
                    name="Failure",
                    description="desc",
                    root_cause="cause",
                )
            ],
            synthesis_notes="Test",
        )
        monkeypatch.setattr(
            optimizer,
            "_hierarchical_root_cause_analysis",
            lambda evaluation_result: analysis,
        )

        improvement_calls: list[int] = []

        def fake_generate_and_evaluate_improvement(**kwargs):
            improvement_calls.append(kwargs.get("attempt", 0))
            prompts_arg = kwargs.get("best_prompts", {})
            improved = {name: p.copy() for name, p in prompts_arg.items()}
            return improved, 0.2, _mock_experiment_result(0.2)

        monkeypatch.setattr(
            optimizer,
            "_generate_and_evaluate_improvement",
            fake_generate_and_evaluate_improvement,
        )

        result = optimizer.optimize_prompt(
            prompt=prompt,
            dataset=dataset,
            metric=_metric,
            max_trials=1,
        )

        assert len(improvement_calls) == 1
        assert result.details["trials_completed"] == 1
        assert len(result.history) == 1
        assert result.history[0]["trials_completed"] == 1

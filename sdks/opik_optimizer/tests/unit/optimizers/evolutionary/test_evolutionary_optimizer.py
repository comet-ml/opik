# mypy: disable-error-code=no-untyped-def

from collections.abc import Callable
from typing import Any
from unittest.mock import MagicMock

import pytest

from opik import Dataset
from opik_optimizer import ChatPrompt, EvolutionaryOptimizer, OptimizationResult
from opik_optimizer.algorithms.evolutionary_optimizer.ops import (
    evaluation_ops,
    population_ops,
)


def _metric(dataset_item: dict[str, Any], llm_output: str) -> float:
    return 1.0


def _make_dataset() -> MagicMock:
    dataset = MagicMock(spec=Dataset)
    dataset.name = "test-dataset"
    dataset.id = "dataset-123"
    dataset.get_items.return_value = [{"id": "1", "question": "Q1", "answer": "A1"}]
    return dataset


class TestEvolutionaryOptimizerInit:
    def test_initialization_with_defaults(self) -> None:
        optimizer = EvolutionaryOptimizer(model="gpt-4o")
        assert optimizer.model == "gpt-4o"
        assert optimizer.seed == 42

    def test_initialization_with_custom_params(self) -> None:
        optimizer = EvolutionaryOptimizer(
            model="gpt-4o-mini",
            verbose=0,
            seed=123,
            enable_moo=True,
        )
        assert optimizer.model == "gpt-4o-mini"
        assert optimizer.verbose == 0
        assert optimizer.seed == 123
        assert optimizer.enable_moo is True


class TestEvolutionaryOptimizerOptimizePrompt:
    def test_single_prompt_returns_chat_prompt(
        self,
        mock_full_optimization_flow,
    ) -> None:
        mock_full_optimization_flow(
            llm_response="Improved prompt",
            evaluation_scores=[0.5, 0.8],
        )

        optimizer = EvolutionaryOptimizer(model="gpt-4o-mini", verbose=0, seed=42)
        prompt = ChatPrompt(system="Test", user="{question}")
        dataset = _make_dataset()

        result = optimizer.optimize_prompt(
            prompt=prompt,
            dataset=dataset,
            metric=_metric,
            max_trials=1,
            n_samples=2,
        )

        assert isinstance(result, OptimizationResult)
        assert isinstance(result.prompt, ChatPrompt)
        assert isinstance(result.initial_prompt, ChatPrompt)

    def test_dict_prompt_returns_dict(
        self,
        mock_full_optimization_flow,
    ) -> None:
        mock_full_optimization_flow(
            llm_response="Improved prompt",
            evaluation_scores=[0.5, 0.8],
        )

        optimizer = EvolutionaryOptimizer(model="gpt-4o-mini", verbose=0, seed=42)
        prompts = {
            "main": ChatPrompt(name="main", system="Main", user="{question}"),
            "secondary": ChatPrompt(
                name="secondary", system="Secondary", user="{input}"
            ),
        }
        dataset = _make_dataset()

        result = optimizer.optimize_prompt(
            prompt=prompts,
            dataset=dataset,
            metric=_metric,
            max_trials=1,
            n_samples=2,
        )

        assert isinstance(result, OptimizationResult)
        assert isinstance(result.prompt, dict)
        assert isinstance(result.initial_prompt, dict)

    def test_invalid_prompt_raises_error(
        self,
        mock_full_optimization_flow,
    ) -> None:
        mock_full_optimization_flow()
        optimizer = EvolutionaryOptimizer(model="gpt-4o-mini", verbose=0, seed=42)
        dataset = _make_dataset()

        with pytest.raises((ValueError, TypeError)):
            optimizer.optimize_prompt(
                prompt="invalid string",  # type: ignore[arg-type]
                dataset=dataset,
                metric=_metric,
                max_trials=1,
            )

    def test_result_contains_required_fields(
        self,
        mock_full_optimization_flow,
    ) -> None:
        mock_full_optimization_flow(
            llm_response="Improved prompt",
            evaluation_scores=[0.5, 0.8],
        )

        optimizer = EvolutionaryOptimizer(model="gpt-4o-mini", verbose=0, seed=42)
        prompt = ChatPrompt(system="Test", user="{question}")
        dataset = _make_dataset()

        result = optimizer.optimize_prompt(
            prompt=prompt,
            dataset=dataset,
            metric=_metric,
            max_trials=1,
            n_samples=2,
        )

        assert result.optimizer == "EvolutionaryOptimizer"
        assert result.prompt is not None
        assert result.initial_prompt is not None
        assert isinstance(result.score, (int, float))
        assert hasattr(result, "history")
        assert hasattr(result, "details")


class TestEvolutionaryOptimizerEarlyStop:
    def test_skips_on_perfect_score(
        self,
        mock_opik_client: Callable[..., MagicMock],
        monkeypatch: pytest.MonkeyPatch,
    ) -> None:
        mock_opik_client()
        dataset = _make_dataset()
        optimizer = EvolutionaryOptimizer(
            model="gpt-4o", perfect_score=0.95, enable_moo=False
        )

        monkeypatch.setattr(
            evaluation_ops, "evaluate_bundle", lambda *args, **kwargs: 0.96
        )
        monkeypatch.setattr(
            population_ops,
            "initialize_population",
            lambda **kwargs: (_ for _ in ()).throw(AssertionError("should not run")),
        )

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
        assert result.details["trials_used"] == 0

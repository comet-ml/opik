# mypy: disable-error-code=no-untyped-def

from collections.abc import Callable
from typing import Any
from unittest.mock import MagicMock

import pytest

from opik import Dataset
from opik_optimizer import ChatPrompt, GepaOptimizer, OptimizationResult


def _metric(dataset_item: dict[str, Any], llm_output: str) -> float:
    return 1.0


def _make_dataset() -> MagicMock:
    dataset = MagicMock(spec=Dataset)
    dataset.name = "test-dataset"
    dataset.id = "dataset-123"
    dataset.get_items.return_value = [{"id": "1", "question": "Q1", "answer": "A1"}]
    return dataset


class TestGepaOptimizerInit:
    def test_initialization_with_defaults(self) -> None:
        optimizer = GepaOptimizer(model="gpt-4o")
        assert optimizer.model == "gpt-4o"
        assert optimizer.seed == 42

    def test_initialization_with_custom_params(self) -> None:
        optimizer = GepaOptimizer(
            model="gpt-4o-mini",
            verbose=0,
            seed=123,
        )
        assert optimizer.model == "gpt-4o-mini"
        assert optimizer.verbose == 0
        assert optimizer.seed == 123


class TestGepaOptimizerOptimizePrompt:
    def test_single_prompt_returns_chat_prompt(
        self,
        mock_optimization_context,
        monkeypatch,
    ) -> None:
        mock_optimization_context()

        optimizer = GepaOptimizer(model="gpt-4o-mini", verbose=0, seed=42)
        prompt = ChatPrompt(system="Test", user="{question}")
        dataset = _make_dataset()

        monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **kwargs: 0.5)

        mock_gepa_result = MagicMock()
        mock_gepa_result.best_prompt = prompt.copy()
        mock_gepa_result.best_score = 0.85
        mock_gepa_result.history = []
        mock_gepa_result.pareto_front = []
        mock_gepa_result.total_metric_calls = 1

        monkeypatch.setattr("gepa.optimize", lambda **kwargs: mock_gepa_result)

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

        optimizer = GepaOptimizer(model="gpt-4o-mini", verbose=0, seed=42)
        prompts = {
            "main": ChatPrompt(name="main", system="Main", user="{question}"),
            "secondary": ChatPrompt(name="secondary", system="Secondary", user="{input}"),
        }
        dataset = _make_dataset()

        monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **kwargs: 0.5)

        mock_gepa_result = MagicMock()
        mock_gepa_result.best_prompt = list(prompts.values())[0].copy()
        mock_gepa_result.best_score = 0.85
        mock_gepa_result.history = []
        mock_gepa_result.pareto_front = []
        mock_gepa_result.total_metric_calls = 1

        monkeypatch.setattr("gepa.optimize", lambda **kwargs: mock_gepa_result)

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
        optimizer = GepaOptimizer(model="gpt-4o-mini", verbose=0, seed=42)
        dataset = _make_dataset()

        with pytest.raises((ValueError, TypeError)):
            optimizer.optimize_prompt(
                prompt="invalid string",  # type: ignore[arg-type]
                dataset=dataset,
                metric=_metric,
                max_trials=1,
            )


class TestGepaOptimizerEarlyStop:
    def test_skips_on_perfect_score(
        self,
        mock_opik_client: Callable[..., MagicMock],
        monkeypatch: pytest.MonkeyPatch,
    ) -> None:
        mock_opik_client()
        dataset = _make_dataset()
        optimizer = GepaOptimizer(model="gpt-4o", perfect_score=0.95)

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
        assert result.details["trials_used"] == 0

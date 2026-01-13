# mypy: disable-error-code=no-untyped-def

from collections.abc import Callable
from typing import Any
from unittest.mock import MagicMock

import pytest

from opik import Dataset
from opik_optimizer import ChatPrompt, FewShotBayesianOptimizer, OptimizationResult


def _metric(dataset_item: dict[str, Any], llm_output: str) -> float:
    return 1.0


def _make_dataset() -> MagicMock:
    dataset = MagicMock(spec=Dataset)
    dataset.name = "test-dataset"
    dataset.id = "dataset-123"
    dataset.get_items.return_value = [{"id": "1", "question": "Q1", "answer": "A1"}]
    return dataset


class TestFewShotBayesianOptimizerInit:
    def test_initialization_with_defaults(self) -> None:
        optimizer = FewShotBayesianOptimizer(model="gpt-4o")
        assert optimizer.model == "gpt-4o"
        assert optimizer.seed == 42

    def test_initialization_with_custom_params(self) -> None:
        optimizer = FewShotBayesianOptimizer(
            model="gpt-4o-mini",
            verbose=0,
            seed=123,
            min_examples=1,
            max_examples=5,
        )
        assert optimizer.model == "gpt-4o-mini"
        assert optimizer.verbose == 0
        assert optimizer.seed == 123
        assert optimizer.min_examples == 1
        assert optimizer.max_examples == 5


class TestFewShotBayesianOptimizerOptimizePrompt:
    def test_single_prompt_returns_chat_prompt(
        self,
        mock_optimization_context,
        monkeypatch,
    ) -> None:
        mock_optimization_context()

        optimizer = FewShotBayesianOptimizer(
            model="gpt-4o-mini",
            verbose=0,
            seed=42,
            min_examples=1,
            max_examples=2,
        )
        prompt = ChatPrompt(system="Test", user="{question}")
        dataset = _make_dataset()

        monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **kwargs: 0.5)

        def mock_create_fewshot_template(**kwargs):
            prompts = kwargs.get("prompts", {})
            return {
                name: p.copy() for name, p in prompts.items()
            }, "Examples:\n{examples}"

        monkeypatch.setattr(
            optimizer, "_create_fewshot_prompt_template", mock_create_fewshot_template
        )

        def mock_run_optimization(**kwargs):
            prompts = kwargs.get("prompts", {})
            original_prompts = kwargs.get("original_prompts", prompts)
            is_single = kwargs.get("is_single_prompt_optimization", True)
            best_prompt = list(prompts.values())[0] if is_single else prompts
            initial_prompt = (
                list(original_prompts.values())[0] if is_single else original_prompts
            )
            return OptimizationResult(
                optimizer="FewShotBayesianOptimizer",
                prompt=best_prompt,
                score=0.85,
                metric_name="test_metric",
                initial_prompt=initial_prompt,
                initial_score=0.5,
                details={},
                history=[],
            )

        monkeypatch.setattr(optimizer, "_run_optimization", mock_run_optimization)

        result = optimizer.optimize_prompt(
            prompt=prompt,
            dataset=dataset,
            metric=_metric,
            max_trials=2,
            n_samples=2,
        )

        assert isinstance(result, OptimizationResult)
        assert isinstance(result.prompt, ChatPrompt)
        assert isinstance(result.initial_prompt, ChatPrompt)

    def test_dict_prompt_returns_dict(
        self,
        mock_optimization_context,
        monkeypatch,
    ) -> None:
        mock_optimization_context()

        optimizer = FewShotBayesianOptimizer(
            model="gpt-4o-mini",
            verbose=0,
            seed=42,
            min_examples=1,
            max_examples=2,
        )
        prompts = {
            "main": ChatPrompt(name="main", system="Main", user="{question}"),
            "secondary": ChatPrompt(
                name="secondary", system="Secondary", user="{input}"
            ),
        }
        dataset = _make_dataset()

        monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **kwargs: 0.5)

        def mock_create_fewshot_template(**kwargs):
            prompts_arg = kwargs.get("prompts", {})
            return {
                name: p.copy() for name, p in prompts_arg.items()
            }, "Examples:\n{examples}"

        monkeypatch.setattr(
            optimizer, "_create_fewshot_prompt_template", mock_create_fewshot_template
        )

        def mock_run_optimization(**kwargs):
            prompts_arg = kwargs.get("prompts", {})
            original_prompts = kwargs.get("original_prompts", prompts_arg)
            is_single = kwargs.get("is_single_prompt_optimization", False)
            best_prompt = list(prompts_arg.values())[0] if is_single else prompts_arg
            initial_prompt = (
                list(original_prompts.values())[0] if is_single else original_prompts
            )
            return OptimizationResult(
                optimizer="FewShotBayesianOptimizer",
                prompt=best_prompt,
                score=0.85,
                metric_name="test_metric",
                initial_prompt=initial_prompt,
                initial_score=0.5,
                details={},
                history=[],
            )

        monkeypatch.setattr(optimizer, "_run_optimization", mock_run_optimization)

        result = optimizer.optimize_prompt(
            prompt=prompts,
            dataset=dataset,
            metric=_metric,
            max_trials=2,
            n_samples=2,
        )

        assert isinstance(result, OptimizationResult)
        assert isinstance(result.prompt, dict)
        assert isinstance(result.initial_prompt, dict)

    def test_invalid_prompt_raises_error(
        self,
        mock_optimization_context,
    ) -> None:
        mock_optimization_context()
        optimizer = FewShotBayesianOptimizer(model="gpt-4o-mini", verbose=0, seed=42)
        dataset = _make_dataset()

        with pytest.raises((ValueError, TypeError)):
            optimizer.optimize_prompt(
                prompt="invalid string",  # type: ignore[arg-type]
                dataset=dataset,
                metric=_metric,
                max_trials=1,
            )


class TestFewShotBayesianOptimizerEarlyStop:
    def test_skips_on_perfect_score(
        self,
        mock_opik_client: Callable[..., MagicMock],
        monkeypatch: pytest.MonkeyPatch,
    ) -> None:
        mock_opik_client()
        dataset = _make_dataset()
        optimizer = FewShotBayesianOptimizer(model="gpt-4o", perfect_score=0.95)

        monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **kwargs: 0.96)
        monkeypatch.setattr(
            optimizer,
            "_run_optimization",
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

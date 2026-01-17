# mypy: disable-error-code=no-untyped-def

from collections.abc import Callable
from typing import Any
from unittest.mock import MagicMock

import pytest

from opik_optimizer import (
    AlgorithmResult,
    ChatPrompt,
    FewShotBayesianOptimizer,
    OptimizationResult,
)
from tests.unit.test_helpers import (
    make_mock_dataset,
    make_simple_metric,
    STANDARD_DATASET_ITEMS,
)


class TestFewShotBayesianOptimizerInit:
    @pytest.mark.parametrize(
        "kwargs,expected",
        [
            ({"model": "gpt-4o"}, {"model": "gpt-4o", "seed": 42}),
            (
                {
                    "model": "gpt-4o-mini",
                    "verbose": 0,
                    "seed": 123,
                    "min_examples": 1,
                    "max_examples": 5,
                },
                {
                    "model": "gpt-4o-mini",
                    "verbose": 0,
                    "seed": 123,
                    "min_examples": 1,
                    "max_examples": 5,
                },
            ),
        ],
    )
    def test_initialization(
        self, kwargs: dict[str, Any], expected: dict[str, Any]
    ) -> None:
        """Test optimizer initialization with defaults and custom params."""
        optimizer = FewShotBayesianOptimizer(**kwargs)
        for key, value in expected.items():
            assert getattr(optimizer, key) == value


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
        dataset = make_mock_dataset(
            STANDARD_DATASET_ITEMS, name="test-dataset", dataset_id="dataset-123"
        )

        monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **kwargs: 0.5)

        def mock_create_fewshot_template(**kwargs):
            prompts = kwargs.get("prompts", {})
            return {
                name: p.copy() for name, p in prompts.items()
            }, "Examples:\n{examples}"

        monkeypatch.setattr(
            optimizer, "_create_fewshot_prompt_template", mock_create_fewshot_template
        )

        def mock_run_optimization(context):
            prompts = context.prompts
            original_prompts = context.initial_prompts
            best_prompt = prompts
            initial_prompt = original_prompts
            return AlgorithmResult(
                best_prompts=best_prompt,
                best_score=0.85,
                history=optimizer.get_history_entries(),
                metadata={
                    "initial_prompt": initial_prompt,
                    "initial_score": 0.5,
                    "metric_name": "test_metric",
                },
            )

        monkeypatch.setattr(optimizer, "run_optimization", mock_run_optimization)

        result = optimizer.optimize_prompt(
            prompt=prompt,
            dataset=dataset,
            metric=make_simple_metric(),
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
        dataset = make_mock_dataset(
            STANDARD_DATASET_ITEMS, name="test-dataset", dataset_id="dataset-123"
        )

        monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **kwargs: 0.5)

        def mock_create_fewshot_template(**kwargs):
            prompts_arg = kwargs.get("prompts", {})
            return {
                name: p.copy() for name, p in prompts_arg.items()
            }, "Examples:\n{examples}"

        monkeypatch.setattr(
            optimizer, "_create_fewshot_prompt_template", mock_create_fewshot_template
        )

        def mock_run_optimization(context):
            prompts_arg = context.prompts
            original_prompts = context.initial_prompts
            is_single = context.is_single_prompt_optimization
            best_prompt = (
                prompts_arg if not is_single else list(prompts_arg.values())[0]
            )
            initial_prompt = (
                original_prompts
                if not is_single
                else list(original_prompts.values())[0]
            )
            return AlgorithmResult(
                best_prompts=best_prompt,
                best_score=0.85,
                history=optimizer.get_history_entries(),
                metadata={
                    "initial_prompt": initial_prompt,
                    "initial_score": 0.5,
                    "metric_name": "test_metric",
                },
            )

        monkeypatch.setattr(optimizer, "run_optimization", mock_run_optimization)

        result = optimizer.optimize_prompt(
            prompt=prompts,
            dataset=dataset,
            metric=make_simple_metric(),
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
        dataset = make_mock_dataset(
            STANDARD_DATASET_ITEMS, name="test-dataset", dataset_id="dataset-123"
        )

        with pytest.raises((ValueError, TypeError)):
            optimizer.optimize_prompt(
                prompt="invalid string",  # type: ignore[arg-type]
                dataset=dataset,
                metric=make_simple_metric(),
                max_trials=1,
            )


class TestFewShotBayesianOptimizerEarlyStop:
    def test_skips_on_perfect_score(
        self,
        mock_opik_client: Callable[..., MagicMock],
        monkeypatch: pytest.MonkeyPatch,
    ) -> None:
        mock_opik_client()
        dataset = make_mock_dataset(
            STANDARD_DATASET_ITEMS, name="test-dataset", dataset_id="dataset-123"
        )
        optimizer = FewShotBayesianOptimizer(model="gpt-4o", perfect_score=0.95)

        monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **kwargs: 0.96)
        monkeypatch.setattr(
            optimizer,
            "run_optimization",
            lambda context: (_ for _ in ()).throw(AssertionError("should not run")),
        )

        prompt = ChatPrompt(system="baseline", user="{question}")
        result = optimizer.optimize_prompt(
            prompt=prompt,
            dataset=dataset,
            metric=make_simple_metric(),
            max_trials=1,
        )

        assert result.details["stopped_early"] is True
        assert result.details["stop_reason"] == "baseline_score_met_threshold"
        assert result.details["perfect_score"] == 0.95
        assert result.initial_score == result.score

    def test_early_stop_reports_at_least_one_trial(
        self,
        mock_opik_client: Callable[..., MagicMock],
        monkeypatch: pytest.MonkeyPatch,
    ) -> None:
        """Verify FewShotBayesianOptimizer early stop reports at least 1 trial."""
        mock_opik_client()
        dataset = make_mock_dataset(
            STANDARD_DATASET_ITEMS, name="test-dataset", dataset_id="dataset-123"
        )
        optimizer = FewShotBayesianOptimizer(model="gpt-4o", perfect_score=0.95)

        monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **kwargs: 0.96)

        prompt = ChatPrompt(system="baseline", user="{question}")
        result = optimizer.optimize_prompt(
            prompt=prompt,
            dataset=dataset,
            metric=make_simple_metric(),
            max_trials=1,
        )

        assert result.details["stopped_early"] is True
        # Early stop happens before _run_optimization, so only baseline was evaluated
        # The optimizer returns 0 from get_metadata (no optimization trials yet)
        # The base class defaults this to 1 to reflect the baseline evaluation
        assert result.details["trials_completed"] == 1
        assert len(result.history) == 1

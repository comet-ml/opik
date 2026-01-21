# mypy: disable-error-code=no-untyped-def

from __future__ import annotations

from typing import Any

import pytest

from opik_optimizer import ChatPrompt, MetaPromptOptimizer, OptimizationResult
from tests.unit.fixtures import assert_invalid_prompt_raises, make_two_prompt_bundle


class TestMetaPromptOptimizerOptimizePrompt:
    def test_single_prompt_returns_chat_prompt(
        self,
        mock_full_optimization_flow,
        mock_dataset,
        sample_dataset_items,
        sample_metric,
    ) -> None:
        mock_full_optimization_flow(
            llm_response="Improved prompt",
            evaluation_scores=[0.5, 0.8],
        )

        optimizer = MetaPromptOptimizer(model="gpt-4o-mini", verbose=0, seed=42)
        prompt = ChatPrompt(system="Test", user="{question}")
        dataset = mock_dataset(
            sample_dataset_items, name="test-dataset", dataset_id="dataset-123"
        )

        result = optimizer.optimize_prompt(
            prompt=prompt,
            dataset=dataset,
            metric=sample_metric,
            max_trials=1,
            n_samples=2,
        )

        assert isinstance(result, OptimizationResult)
        assert isinstance(result.prompt, ChatPrompt)
        assert isinstance(result.initial_prompt, ChatPrompt)

    def test_dict_prompt_returns_dict(
        self,
        mock_full_optimization_flow,
        mock_dataset,
        sample_dataset_items,
        sample_metric,
    ) -> None:
        mock_full_optimization_flow(
            llm_response="Improved prompt",
            evaluation_scores=[0.5, 0.8],
        )

        optimizer = MetaPromptOptimizer(model="gpt-4o-mini", verbose=0, seed=42)
        prompts = make_two_prompt_bundle()
        dataset = mock_dataset(
            sample_dataset_items, name="test-dataset", dataset_id="dataset-123"
        )

        result = optimizer.optimize_prompt(
            prompt=prompts,
            dataset=dataset,
            metric=sample_metric,
            max_trials=1,
            n_samples=2,
        )

        assert isinstance(result, OptimizationResult)
        assert isinstance(result.prompt, dict)

    def test_sets_reporter_during_optimization(
        self,
        mock_full_optimization_flow,
        monkeypatch: pytest.MonkeyPatch,
        mock_dataset,
        sample_dataset_items,
        sample_metric,
    ) -> None:
        mock_full_optimization_flow(
            llm_response="Improved prompt",
            evaluation_scores=[0.5, 0.8],
        )

        optimizer = MetaPromptOptimizer(model="gpt-4o-mini", verbose=0, seed=42)
        prompt = ChatPrompt(system="Test", user="{question}")
        dataset = mock_dataset(
            sample_dataset_items, name="test-dataset", dataset_id="dataset-123"
        )

        events: list[str] = []

        orig_set = optimizer._set_reporter
        orig_clear = optimizer._clear_reporter

        def tracking_set(reporter: Any) -> None:
            events.append("set")
            orig_set(reporter)

        def tracking_clear() -> None:
            events.append("clear")
            orig_clear()

        monkeypatch.setattr(optimizer, "_set_reporter", tracking_set)
        monkeypatch.setattr(optimizer, "_clear_reporter", tracking_clear)

        optimizer.optimize_prompt(
            prompt=prompt,
            dataset=dataset,
            metric=sample_metric,
            max_trials=1,
            n_samples=2,
        )

        assert events == ["set", "clear"]
        assert optimizer._reporter is None

    def test_invalid_prompt_raises_error(
        self,
        mock_full_optimization_flow,
        mock_dataset,
        sample_dataset_items,
        sample_metric,
    ) -> None:
        mock_full_optimization_flow()
        optimizer = MetaPromptOptimizer(model="gpt-4o-mini", verbose=0, seed=42)
        dataset = mock_dataset(
            sample_dataset_items, name="test-dataset", dataset_id="dataset-123"
        )

        assert_invalid_prompt_raises(
            optimizer,
            dataset=dataset,
            metric=sample_metric,
            max_trials=1,
        )

    def test_result_contains_required_fields(
        self,
        mock_full_optimization_flow,
        mock_dataset,
        sample_dataset_items,
        sample_metric,
    ) -> None:
        mock_full_optimization_flow(
            llm_response="Improved prompt",
            evaluation_scores=[0.5, 0.8],
        )

        optimizer = MetaPromptOptimizer(model="gpt-4o-mini", verbose=0, seed=42)
        prompt = ChatPrompt(system="Test", user="{question}")
        dataset = mock_dataset(
            sample_dataset_items, name="test-dataset", dataset_id="dataset-123"
        )

        result = optimizer.optimize_prompt(
            prompt=prompt,
            dataset=dataset,
            metric=sample_metric,
            max_trials=1,
            n_samples=2,
        )

        assert result.optimizer == "MetaPromptOptimizer"
        assert result.prompt is not None
        assert result.initial_prompt is not None
        assert isinstance(result.score, (int, float))
        assert hasattr(result, "history")
        assert hasattr(result, "details")


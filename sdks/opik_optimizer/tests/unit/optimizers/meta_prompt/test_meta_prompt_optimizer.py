# mypy: disable-error-code=no-untyped-def

from collections.abc import Callable
from typing import Any
from unittest.mock import MagicMock

import pytest

from opik import Dataset
from opik_optimizer import ChatPrompt, MetaPromptOptimizer, OptimizationResult
from opik_optimizer.algorithms.meta_prompt_optimizer.ops import candidate_ops


def _metric(dataset_item: dict[str, Any], llm_output: str) -> float:
    return 1.0


def _make_dataset() -> MagicMock:
    dataset = MagicMock(spec=Dataset)
    dataset.name = "test-dataset"
    dataset.id = "dataset-123"
    dataset.get_items.return_value = [{"id": "1", "question": "Q1", "answer": "A1"}]
    return dataset


class TestMetaPromptOptimizerInit:
    def test_initialization_with_defaults(self) -> None:
        optimizer = MetaPromptOptimizer(model="gpt-4o")
        assert optimizer.model == "gpt-4o"
        assert optimizer.seed == 42

    def test_initialization_with_custom_params(self) -> None:
        optimizer = MetaPromptOptimizer(
            model="gpt-4o-mini",
            verbose=0,
            seed=123,
        )
        assert optimizer.model == "gpt-4o-mini"
        assert optimizer.verbose == 0
        assert optimizer.seed == 123


class TestMetaPromptOptimizerOptimizePrompt:
    def test_single_prompt_returns_chat_prompt(
        self,
        mock_full_optimization_flow,
    ) -> None:
        mock_full_optimization_flow(
            llm_response="Improved prompt",
            evaluation_scores=[0.5, 0.8],
        )

        optimizer = MetaPromptOptimizer(model="gpt-4o-mini", verbose=0, seed=42)
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

        optimizer = MetaPromptOptimizer(model="gpt-4o-mini", verbose=0, seed=42)
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
        optimizer = MetaPromptOptimizer(model="gpt-4o-mini", verbose=0, seed=42)
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

        optimizer = MetaPromptOptimizer(model="gpt-4o-mini", verbose=0, seed=42)
        prompt = ChatPrompt(system="Test", user="{question}")
        dataset = _make_dataset()

        result = optimizer.optimize_prompt(
            prompt=prompt,
            dataset=dataset,
            metric=_metric,
            max_trials=1,
            n_samples=2,
        )

        assert result.optimizer == "MetaPromptOptimizer"
        assert result.prompt is not None
        assert result.initial_prompt is not None
        assert isinstance(result.score, (int, float))
        assert hasattr(result, "history")
        assert hasattr(result, "details")


class TestMetaPromptOptimizerMultiPrompt:
    """Tests for multi-prompt (bundle) optimization."""

    def test_dict_prompt_preserves_all_keys(
        self,
        mock_full_optimization_flow,
    ) -> None:
        """Verify multi-prompt optimization preserves all prompt keys in result."""
        mock_full_optimization_flow(
            llm_response="Improved prompt",
            evaluation_scores=[0.5, 0.8],
        )

        optimizer = MetaPromptOptimizer(model="gpt-4o-mini", verbose=0, seed=42)
        prompts = {
            "system_prompt": ChatPrompt(
                name="system_prompt", system="System", user="{question}"
            ),
            "assistant_prompt": ChatPrompt(
                name="assistant_prompt", system="Assistant", user="{input}"
            ),
            "reviewer_prompt": ChatPrompt(
                name="reviewer_prompt", system="Reviewer", user="{text}"
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

        assert isinstance(result.prompt, dict)
        # Result should have the same keys as input (or a subset if optimization selected fewer)
        assert isinstance(result.initial_prompt, dict)
        # Initial prompt should preserve all original keys
        assert set(result.initial_prompt.keys()) == set(prompts.keys())

    def test_dict_prompt_each_value_is_chat_prompt(
        self,
        mock_full_optimization_flow,
    ) -> None:
        """Verify each prompt in multi-prompt result is a ChatPrompt."""
        mock_full_optimization_flow(
            llm_response="Improved prompt",
            evaluation_scores=[0.5, 0.8],
        )

        optimizer = MetaPromptOptimizer(model="gpt-4o-mini", verbose=0, seed=42)
        prompts = {
            "main": ChatPrompt(name="main", system="Main", user="{question}"),
            "helper": ChatPrompt(name="helper", system="Helper", user="{context}"),
        }
        dataset = _make_dataset()

        result = optimizer.optimize_prompt(
            prompt=prompts,
            dataset=dataset,
            metric=_metric,
            max_trials=1,
            n_samples=2,
        )

        assert isinstance(result.prompt, dict)
        for key, value in result.prompt.items():
            assert isinstance(value, ChatPrompt), f"Prompt {key} should be ChatPrompt"

        assert isinstance(result.initial_prompt, dict)
        for key, value in result.initial_prompt.items():
            assert isinstance(value, ChatPrompt), (
                f"Initial prompt {key} should be ChatPrompt"
            )

    def test_dict_prompt_result_has_score(
        self,
        mock_full_optimization_flow,
    ) -> None:
        """Verify multi-prompt optimization returns proper score."""
        mock_full_optimization_flow(
            llm_response="Improved prompt",
            evaluation_scores=[0.4, 0.9],  # baseline=0.4, improved=0.9
        )

        optimizer = MetaPromptOptimizer(model="gpt-4o-mini", verbose=0, seed=42)
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

        assert isinstance(result.score, (int, float))
        assert result.score >= 0
        assert result.initial_score is not None
        assert isinstance(result.initial_score, (int, float))

    def test_dict_prompt_result_has_details(
        self,
        mock_full_optimization_flow,
    ) -> None:
        """Verify multi-prompt optimization result contains proper details."""
        mock_full_optimization_flow(
            llm_response="Improved prompt",
            evaluation_scores=[0.5, 0.8],
        )

        optimizer = MetaPromptOptimizer(model="gpt-4o-mini", verbose=0, seed=42)
        prompts = {
            "agent1": ChatPrompt(name="agent1", system="Agent 1", user="{question}"),
            "agent2": ChatPrompt(name="agent2", system="Agent 2", user="{input}"),
        }
        dataset = _make_dataset()

        result = optimizer.optimize_prompt(
            prompt=prompts,
            dataset=dataset,
            metric=_metric,
            max_trials=1,
            n_samples=2,
        )

        assert result.optimizer == "MetaPromptOptimizer"
        assert hasattr(result, "details")
        assert isinstance(result.details, dict)
        assert hasattr(result, "history")


class TestMetaPromptOptimizerEarlyStop:
    def test_skips_on_perfect_score(
        self,
        mock_opik_client: Callable[..., MagicMock],
        monkeypatch: pytest.MonkeyPatch,
    ) -> None:
        mock_opik_client()
        dataset = _make_dataset()
        optimizer = MetaPromptOptimizer(model="gpt-4o", perfect_score=0.95)

        monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **kwargs: 0.96)
        monkeypatch.setattr(
            candidate_ops,
            "generate_agent_bundle_candidates",
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

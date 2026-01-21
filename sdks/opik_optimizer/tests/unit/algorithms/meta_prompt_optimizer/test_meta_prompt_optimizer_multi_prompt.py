# mypy: disable-error-code=no-untyped-def

from __future__ import annotations

from opik_optimizer import ChatPrompt, MetaPromptOptimizer, OptimizationResult
from tests.unit.fixtures import make_two_prompt_bundle


class TestMetaPromptOptimizerMultiPrompt:
    """Tests for multi-prompt (bundle) optimization."""

    def test_dict_prompt_preserves_all_keys(
        self,
        mock_full_optimization_flow,
        mock_dataset,
        sample_dataset_items,
        sample_metric,
    ) -> None:
        """Verify multi-prompt optimization preserves all prompt keys in result."""
        mock_full_optimization_flow(
            llm_response="Improved prompt",
            evaluation_scores=[0.5, 0.8],
        )

        optimizer = MetaPromptOptimizer(model="gpt-4o-mini", verbose=0, seed=42)
        prompts = {
            "system_prompt": ChatPrompt(name="system_prompt", system="System", user="{question}"),
            "assistant_prompt": ChatPrompt(
                name="assistant_prompt", system="Assistant", user="{input}"
            ),
            "reviewer_prompt": ChatPrompt(name="reviewer_prompt", system="Reviewer", user="{text}"),
        }
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
        assert isinstance(result.initial_prompt, dict)
        assert set(result.initial_prompt.keys()) == set(prompts.keys())

    def test_dict_prompt_each_value_is_chat_prompt(
        self,
        mock_full_optimization_flow,
        mock_dataset,
        sample_dataset_items,
        sample_metric,
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
        mock_dataset,
        sample_dataset_items,
        sample_metric,
    ) -> None:
        """Verify multi-prompt optimization returns proper score."""
        mock_full_optimization_flow(
            llm_response="Improved prompt",
            evaluation_scores=[0.4, 0.9],  # baseline=0.4, improved=0.9
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

        assert isinstance(result.score, (int, float))
        assert result.score >= 0
        assert result.initial_score is not None
        assert isinstance(result.initial_score, (int, float))

    def test_dict_prompt_result_has_details(
        self,
        mock_full_optimization_flow,
        mock_dataset,
        sample_dataset_items,
        sample_metric,
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

        assert result.optimizer == "MetaPromptOptimizer"
        assert isinstance(result.details, dict)
        assert hasattr(result, "history")


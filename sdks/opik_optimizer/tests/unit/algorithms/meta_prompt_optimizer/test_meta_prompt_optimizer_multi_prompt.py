# mypy: disable-error-code=no-untyped-def

from __future__ import annotations

from opik_optimizer import ChatPrompt, MetaPromptOptimizer, OptimizationResult


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

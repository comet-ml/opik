# mypy: disable-error-code=no-untyped-def

from opik_optimizer import ChatPrompt, EvolutionaryOptimizer, OptimizationResult
from tests.unit.fixtures import assert_invalid_prompt_raises, make_two_prompt_bundle
from tests.unit.test_helpers import (
    STANDARD_DATASET_ITEMS,
    make_mock_dataset,
    make_simple_metric,
)


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
        dataset = make_mock_dataset(
            STANDARD_DATASET_ITEMS, name="test-dataset", dataset_id="dataset-123"
        )

        result = optimizer.optimize_prompt(
            prompt=prompt,
            dataset=dataset,
            metric=make_simple_metric(),
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
        prompts = make_two_prompt_bundle()
        dataset = make_mock_dataset(
            STANDARD_DATASET_ITEMS, name="test-dataset", dataset_id="dataset-123"
        )

        result = optimizer.optimize_prompt(
            prompt=prompts,
            dataset=dataset,
            metric=make_simple_metric(),
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
        dataset = make_mock_dataset(
            STANDARD_DATASET_ITEMS, name="test-dataset", dataset_id="dataset-123"
        )

        assert_invalid_prompt_raises(
            optimizer,
            dataset=dataset,
            metric=make_simple_metric(),
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
        dataset = make_mock_dataset(
            STANDARD_DATASET_ITEMS, name="test-dataset", dataset_id="dataset-123"
        )

        result = optimizer.optimize_prompt(
            prompt=prompt,
            dataset=dataset,
            metric=make_simple_metric(),
            max_trials=1,
            n_samples=2,
        )

        assert result.optimizer == "EvolutionaryOptimizer"
        assert result.prompt is not None
        assert result.initial_prompt is not None
        assert isinstance(result.score, (int, float))
        assert hasattr(result, "history")
        assert hasattr(result, "details")


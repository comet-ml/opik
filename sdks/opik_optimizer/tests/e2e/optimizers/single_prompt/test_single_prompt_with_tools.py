"""
E2E tests for single prompt optimization with tools/function_map preservation.

Tests verify that all prompt-focused optimizers:
1. Complete optimization successfully with tools configured
2. Preserve tools definition exactly
3. Preserve function_map keys
4. Produce valid optimized prompt structure
"""

import os
import copy

import pytest
from typing import Any

import opik
from opik.evaluation.metrics import LevenshteinRatio
from opik.evaluation.metrics.score_result import ScoreResult

import opik_optimizer
from opik_optimizer import (
    ChatPrompt,
    EvolutionaryOptimizer,
    MetaPromptOptimizer,
    FewShotBayesianOptimizer,
    GepaOptimizer,
    HierarchicalReflectiveOptimizer,
)

from ..utils import (
    mock_calculator,
    mock_search,
    CALCULATOR_TOOL,
    SEARCH_TOOL,
)


pytestmark = pytest.mark.integration


# -----------------------------------------------------------------------------
# Helpers
# -----------------------------------------------------------------------------


def levenshtein_metric(dataset_item: dict[str, Any], llm_output: str) -> ScoreResult:
    """Standard Levenshtein ratio metric for testing with reason for HierarchicalReflective."""
    metric = LevenshteinRatio()
    result = metric.score(reference=dataset_item["label"], output=llm_output)
    return ScoreResult(
        name=result.name,
        value=result.value,
        reason=f"Similarity: {result.value:.2f}",
    )


def get_tiny_dataset() -> opik.Dataset:
    """Get the tiny test dataset for fast testing."""
    return opik_optimizer.datasets.tiny_test()


def create_optimizer_config(optimizer_class: type) -> dict[str, Any]:
    """
    Create minimal optimizer configuration for fast testing.
    """
    base_config = {
        "model": "openai/gpt-4o-mini",
        "model_parameters": {
            "temperature": 0.7,
            "max_tokens": 500,
        },
        "verbose": 0,
        "seed": 42,
    }

    optimizer_specific = {
        EvolutionaryOptimizer: {
            "population_size": 2,
            "num_generations": 1,
            "n_threads": 2,
            "enable_llm_crossover": False,
            "enable_moo": False,
            "elitism_size": 1,
        },
        MetaPromptOptimizer: {
            "n_threads": 2,
            "prompts_per_round": 1,
        },
        FewShotBayesianOptimizer: {
            "min_examples": 1,
            "max_examples": 2,
        },
        GepaOptimizer: {
            "n_threads": 2,
        },
        HierarchicalReflectiveOptimizer: {
            "n_threads": 2,
            "max_parallel_batches": 2,
            "batch_size": 2,
            "convergence_threshold": 0.01,
        },
    }

    return {**base_config, **optimizer_specific.get(optimizer_class, {})}


# -----------------------------------------------------------------------------
# Tests
# -----------------------------------------------------------------------------


@pytest.mark.parametrize(
    "optimizer_class",
    [
        EvolutionaryOptimizer,
        MetaPromptOptimizer,
        FewShotBayesianOptimizer,
        GepaOptimizer,
        HierarchicalReflectiveOptimizer,
    ],
)
def test_single_prompt_with_tools(optimizer_class: type) -> None:
    """
    Test that optimizers preserve tools and function_map while optimizing prompts.

    This test verifies:
    1. Optimization completes successfully
    2. Tools definition is preserved exactly
    3. Function map keys are preserved
    4. Optimized prompt has valid structure
    """
    # Skip if no API key
    if not os.getenv("OPENAI_API_KEY"):
        pytest.skip("OPENAI_API_KEY environment variable required")

    # Skip GEPA if not installed
    if optimizer_class == GepaOptimizer:
        import importlib.util

        if importlib.util.find_spec("gepa") is None:
            pytest.skip("gepa package not installed")

    # Create prompt with tools
    original_prompt = ChatPrompt(
        name="tool-prompt",
        system=(
            "You are a helpful assistant that can use tools. "
            "Use the calculator for math operations and search for information."
        ),
        user="{text}",
        tools=[CALCULATOR_TOOL, SEARCH_TOOL],
        function_map={
            "mock_calculator": mock_calculator,
            "mock_search": mock_search,
        },
        model="openai/gpt-4o-mini",
    )

    # Keep a deep copy for comparison
    original_tools = copy.deepcopy(original_prompt.tools)
    original_function_map_keys = set(original_prompt.function_map.keys())

    # Get dataset
    dataset = get_tiny_dataset()

    # Create optimizer with minimal config
    config = create_optimizer_config(optimizer_class)
    optimizer = optimizer_class(**config)

    # Run optimization
    results = optimizer.optimize_prompt(
        dataset=dataset,
        metric=levenshtein_metric,
        prompt=original_prompt,
        n_samples=2,
        max_trials=2,
    )

    # Validate results structure
    assert results.optimizer == optimizer_class.__name__, (
        f"Expected {optimizer_class.__name__}, got {results.optimizer}"
    )
    assert isinstance(results.score, (int, float)), (
        f"Score should be numeric, got {type(results.score)}"
    )

    # Get optimized prompt
    optimized_prompt = results.prompt
    assert isinstance(optimized_prompt, ChatPrompt), (
        f"Optimized prompt should be ChatPrompt, got {type(optimized_prompt)}"
    )

    # Verify tools are preserved
    assert optimized_prompt.tools == original_tools, (
        f"Tools were modified during optimization.\n"
        f"Original: {original_tools}\n"
        f"Optimized: {optimized_prompt.tools}"
    )

    # Verify function_map keys are preserved
    optimized_function_map_keys = set(optimized_prompt.function_map.keys())
    assert optimized_function_map_keys == original_function_map_keys, (
        f"Function map keys were modified.\n"
        f"Original: {original_function_map_keys}\n"
        f"Optimized: {optimized_function_map_keys}"
    )

    # Verify prompt has valid messages
    messages = optimized_prompt.get_messages()
    assert len(messages) > 0, "Optimized prompt should have messages"
    for msg in messages:
        assert "role" in msg, "Message should have 'role' field"
        assert "content" in msg, "Message should have 'content' field"

    print(f"✅ {optimizer_class.__name__}: Single prompt with tools - PASSED")


# -----------------------------------------------------------------------------
# Individual Optimizer Tests (for targeted debugging)
# -----------------------------------------------------------------------------


def test_evolutionary_single_prompt_with_tools() -> None:
    """Test EvolutionaryOptimizer with tools."""
    test_single_prompt_with_tools(EvolutionaryOptimizer)


def test_metaprompt_single_prompt_with_tools() -> None:
    """Test MetaPromptOptimizer with tools."""
    test_single_prompt_with_tools(MetaPromptOptimizer)


def test_fewshot_single_prompt_with_tools() -> None:
    """Test FewShotBayesianOptimizer with tools."""
    test_single_prompt_with_tools(FewShotBayesianOptimizer)


def test_gepa_single_prompt_with_tools() -> None:
    """Test GepaOptimizer with tools."""
    test_single_prompt_with_tools(GepaOptimizer)


def test_hierarchical_single_prompt_with_tools() -> None:
    """Test HierarchicalReflectiveOptimizer with tools."""
    test_single_prompt_with_tools(HierarchicalReflectiveOptimizer)


if __name__ == "__main__":
    # Run tests manually
    for optimizer_class in [
        EvolutionaryOptimizer,
        MetaPromptOptimizer,
        FewShotBayesianOptimizer,
        GepaOptimizer,
        HierarchicalReflectiveOptimizer,
    ]:
        try:
            test_single_prompt_with_tools(optimizer_class)
        except Exception as e:
            print(f"❌ {optimizer_class.__name__}: FAILED - {e}")

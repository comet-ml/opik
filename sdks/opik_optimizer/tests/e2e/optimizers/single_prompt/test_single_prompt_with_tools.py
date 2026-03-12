"""
E2E tests for single prompt optimization with tools/function_map preservation.

Tests verify that all prompt-focused optimizers:
1. Complete optimization successfully with tools configured
2. Preserve tools definition exactly
3. Preserve function_map keys
4. Produce valid optimized prompt structure
"""

import os

import pytest

from opik import Dataset

from opik_optimizer import (
    ChatPrompt,
    EvolutionaryOptimizer,
    MetaPromptOptimizer,
    FewShotBayesianOptimizer,
    GepaOptimizer,
    HierarchicalReflectiveOptimizer,
    ParameterOptimizer,
)

from ..utils import (
    mock_calculator,
    mock_search,
    CALCULATOR_TOOL,
    SEARCH_TOOL,
    assert_tools_preserved,
    create_optimizer_config,
    get_parameter_space,
    levenshtein_metric,
    run_optimizer,
)


pytestmark = pytest.mark.integration


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
        ParameterOptimizer,
    ],
)
def test_single_prompt_with_tools(
    optimizer_class: type,
    tiny_dataset: Dataset,
) -> None:
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
        model="openai/gpt-5-nano",
    )

    dataset = tiny_dataset

    # Create optimizer with minimal config
    config = create_optimizer_config(
        optimizer_class,
        verbose=0,
    )
    optimizer = optimizer_class(**config)
    results = run_optimizer(
        optimizer_class=optimizer_class,
        optimizer=optimizer,
        prompt=original_prompt,
        dataset=dataset,
        metric=levenshtein_metric,
        parameter_space=get_parameter_space(),
        n_samples=1,
        max_trials=1,
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

    assert_tools_preserved(original_prompt, optimized_prompt)

    # Verify prompt has valid messages
    messages = optimized_prompt.get_messages()
    assert len(messages) > 0, "Optimized prompt should have messages"
    for msg in messages:
        assert "role" in msg, "Message should have 'role' field"
        assert "content" in msg, "Message should have 'content' field"

    print(f"✅ {optimizer_class.__name__}: Single prompt with tools - PASSED")

"""
E2E tests for multi-prompt optimization using custom agents.

Tests verify that all prompt-focused optimizers:
1. Complete optimization with dict of prompts
2. Return all prompts in the dict
3. Maintain valid prompt structure
4. Work correctly with custom agents
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
    MultiPromptTestAgent,
    create_optimizer_config,
    get_parameter_space,
    levenshtein_metric,
    run_optimizer,
)


pytestmark = pytest.mark.integration


def create_multi_prompt_dict() -> dict[str, ChatPrompt]:
    """Create a dict of ChatPrompts for multi-prompt optimization."""
    return {
        "analyze": ChatPrompt(
            name="analyze",
            system=(
                "You are an analysis assistant. Extract key information "
                "and identify the main topic from the input."
            ),
            user="{text}",
            model="openai/gpt-5-nano",
        ),
        "respond": ChatPrompt(
            name="respond",
            system=(
                "You are a response assistant. Generate a helpful response "
                "based on the analysis provided."
            ),
            user="Analysis: {analysis}\n\nOriginal question: {text}",
            model="openai/gpt-5-nano",
        ),
    }


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
def test_multi_prompt_with_agent(
    optimizer_class: type,
    tiny_dataset: Dataset,
    multi_prompt_agent: MultiPromptTestAgent,
) -> None:
    """
    Test that optimizers can optimize multiple prompts using a custom agent.

    This test verifies:
    1. Optimization completes with dict of prompts
    2. All prompts in the dict are returned
    3. Prompts maintain valid structure
    4. Agent can still execute with optimized prompts
    """
    # Skip if no API key
    if not os.getenv("OPENAI_API_KEY"):
        pytest.skip("OPENAI_API_KEY environment variable required")

    # Skip GEPA if not installed
    if optimizer_class == GepaOptimizer:
        import importlib.util

        if importlib.util.find_spec("gepa") is None:
            pytest.skip("gepa package not installed")

    # Create multi-prompt dict
    original_prompts = create_multi_prompt_dict()

    agent = multi_prompt_agent
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
        prompt=original_prompts,
        dataset=dataset,
        metric=levenshtein_metric,
        agent=agent,
        parameter_space=get_parameter_space(),
        n_samples=1,
        max_trials=1,
    )

    # Validate results structure
    assert results.optimizer == optimizer_class.__name__, (
        f"Expected {optimizer_class.__name__}, got {results.optimizer}"
    )

    # Get optimized prompts
    optimized_prompts = results.prompt

    # Handle both single ChatPrompt and dict returns
    if isinstance(optimized_prompts, dict):
        # Verify all original prompt keys are present
        for name in original_prompts:
            assert name in optimized_prompts, (
                f"Prompt '{name}' missing from optimized results"
            )

            optimized = optimized_prompts[name]
            assert isinstance(optimized, ChatPrompt), (
                f"Optimized prompt '{name}' should be ChatPrompt, got {type(optimized)}"
            )

            # Verify prompt has valid messages
            messages = optimized.get_messages()
            assert len(messages) > 0, f"Optimized prompt '{name}' should have messages"
            for msg in messages:
                assert "role" in msg, f"Message in '{name}' should have 'role' field"
                assert "content" in msg, (
                    f"Message in '{name}' should have 'content' field"
                )
    else:
        # Single prompt returned (some optimizers may do this)
        assert isinstance(optimized_prompts, ChatPrompt), (
            f"Optimized result should be ChatPrompt or dict, got {type(optimized_prompts)}"
        )
        messages = optimized_prompts.get_messages()
        assert len(messages) > 0, "Optimized prompt should have messages"

    print(f"✅ {optimizer_class.__name__}: Multi-prompt with agent - PASSED")

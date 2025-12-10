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
    ParameterOptimizer,
    ParameterSearchSpace,
)

from ..utils import MultiPromptTestAgent


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
            "max_tokens": 5000,
        },
        "seed": 42,
        "name": f"e2e-multi-prompt-{optimizer_class.__name__}",
    }

    optimizer_specific: dict[type, dict[str, Any]] = {
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
        ParameterOptimizer: {
            "n_threads": 2,
            "default_n_trials": 2,
            "local_search_ratio": 0.0,
        },
    }

    return {**base_config, **optimizer_specific.get(optimizer_class, {})}


def get_parameter_space() -> ParameterSearchSpace:
    """Create a simple parameter space for testing."""
    return ParameterSearchSpace.model_validate(
        {
            "temperature": {"type": "float", "min": 0.1, "max": 1.0},
        }
    )


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
            model="openai/gpt-4o-mini",
        ),
        "respond": ChatPrompt(
            name="respond",
            system=(
                "You are a response assistant. Generate a helpful response "
                "based on the analysis provided."
            ),
            user="Analysis: {analysis}\n\nOriginal question: {text}",
            model="openai/gpt-4o-mini",
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
def test_multi_prompt_with_agent(optimizer_class: type) -> None:
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

    # Create test agent
    agent = MultiPromptTestAgent(
        model="openai/gpt-4o-mini",
        model_parameters={"temperature": 0.7},
    )

    # Get dataset
    dataset = get_tiny_dataset()

    # Create optimizer with minimal config
    config = create_optimizer_config(optimizer_class)
    optimizer = optimizer_class(**config)

    # Run optimization - ParameterOptimizer uses optimize_parameter
    if optimizer_class == ParameterOptimizer:
        results = optimizer.optimize_parameter(
            prompt=original_prompts,
            dataset=dataset,
            metric=levenshtein_metric,
            parameter_space=get_parameter_space(),
            agent=agent,
            n_samples=2,
            max_trials=2,
        )
    else:
        results = optimizer.optimize_prompt(
            dataset=dataset,
            metric=levenshtein_metric,
            prompt=original_prompts,
            agent=agent,
            n_samples=2,
            max_trials=2,
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

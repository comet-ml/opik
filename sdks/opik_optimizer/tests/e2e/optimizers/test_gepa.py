import os
import pytest
from opik.evaluation.metrics import LevenshteinRatio
from opik.evaluation.metrics.score_result import ScoreResult
from typing import Any

# Ensure gepa package is available - fail if not installed
import importlib.util

if importlib.util.find_spec("gepa") is None:
    pytest.fail(
        "gepa package is required for GEPA optimizer tests. Install with: pip install 'opik_optimizer[dev]'"
    )

import opik_optimizer

pytestmark = pytest.mark.integration


def test_gepa_optimizer() -> None:
    # Ensure API key is available for e2e testing
    if not os.getenv("OPENAI_API_KEY"):
        pytest.fail("OPENAI_API_KEY environment variable must be set for e2e tests")

    # Prepare dataset (using tiny_test for faster execution)
    dataset = opik_optimizer.datasets.tiny_test()

    # Define metric and task configuration (see docs for more options)
    def levenshtein_ratio(dataset_item: dict[str, Any], llm_output: str) -> ScoreResult:
        metric = LevenshteinRatio()
        return metric.score(reference=dataset_item["label"], output=llm_output)

    prompt = opik_optimizer.ChatPrompt(
        system="Provide an answer to the question.", user="{text}"
    )

    # Initialize optimizer with reduced parameters for faster testing
    optimizer = opik_optimizer.GepaOptimizer(
        model="openai/gpt-5-mini",
        model_parameters={
            "temperature": 1,
            "max_tokens": 1000,
            "reasoning_effort": "minimal",
        },
        n_threads=2,
        seed=42,
    )

    # Run optimization with reduced sample size
    results = optimizer.optimize_prompt(
        dataset=dataset,
        metric=levenshtein_ratio,
        prompt=prompt,
        n_samples=1,
        max_trials=2,
    )

    # Enhanced OptimizationResult validation

    # Core fields validation - focus on values, not existence
    assert results.optimizer == "GepaOptimizer", (
        f"Expected GepaOptimizer, got {results.optimizer}"
    )
    assert results.dataset_id == dataset.id, (
        f"Expected dataset id {dataset.id}, got {results.dataset_id}"
    )
    assert results.metric_name == "levenshtein_ratio", (
        f"Expected metric name 'levenshtein_ratio', got {results.metric_name}"
    )
    assert results.details.get("model") == optimizer.model, (
        f"Expected model {optimizer.model}, got {results.details.get('model')}"
    )

    # Results structure validation
    assert hasattr(results, "prompt"), "Results should have prompt attribute"
    assert isinstance(results.prompt, list), (
        f"Prompt should be list, got {type(results.prompt)}"
    )
    assert len(results.prompt) > 0, "Prompt list should not be empty"

    # Best prompt validation (first prompt in the list is the best one)
    best_prompt_entry = results.prompt[0]
    assert isinstance(best_prompt_entry, dict), (
        f"Best prompt entry should be dict, got {type(best_prompt_entry)}"
    )
    assert "role" in best_prompt_entry, "Best prompt entry should have 'role' key"
    assert "content" in best_prompt_entry, "Best prompt entry should have 'content' key"

    # Validate that the best prompt has meaningful content
    assert len(best_prompt_entry["content"]) > 0, (
        "Best prompt content should not be empty"
    )
    assert best_prompt_entry["role"] == "system", (
        "Best prompt should be a system prompt"
    )

    # Validate all prompts have the expected structure
    for i, prompt_entry in enumerate(results.prompt):
        assert isinstance(prompt_entry, dict), (
            f"Prompt entry {i} should be dict, got {type(prompt_entry)}"
        )
        assert "role" in prompt_entry, f"Prompt entry {i} should have 'role' key"
        assert "content" in prompt_entry, f"Prompt entry {i} should have 'content' key"
        assert len(prompt_entry["content"]) > 0, (
            f"Prompt entry {i} content should not be empty"
        )

    print("✅ GEPA optimization completed successfully!")
    print(f"   Total prompts: {len(results.prompt)}")
    print(f"   Best prompt content: {best_prompt_entry['content'][:100]}...")

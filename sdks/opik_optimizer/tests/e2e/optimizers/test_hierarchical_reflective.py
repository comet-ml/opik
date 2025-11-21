import os
import pytest
from opik.evaluation.metrics import LevenshteinRatio
from opik.evaluation.metrics.score_result import ScoreResult
from typing import Any

import opik_optimizer

pytestmark = pytest.mark.integration


def test_hierarchical_reflective_optimizer() -> None:
    """E2E test for HierarchicalReflectiveOptimizer."""
    # Ensure API key is available for e2e testing
    if not os.getenv("OPENAI_API_KEY"):
        pytest.fail("OPENAI_API_KEY environment variable must be set for e2e tests")

    # Prepare dataset (using tiny_test for faster execution)
    dataset = opik_optimizer.datasets.tiny_test()

    # Define metric with reason field for hierarchical analysis
    def levenshtein_ratio(dataset_item: dict[str, Any], llm_output: str) -> ScoreResult:
        metric = LevenshteinRatio()
        result = metric.score(reference=dataset_item["label"], output=llm_output)
        # Add reason field required by HierarchicalReflectiveOptimizer
        result.reason = f"Levenshtein similarity between output and reference '{dataset_item['label']}'"
        return result

    # Create initial prompt
    prompt = opik_optimizer.ChatPrompt(
        system="Provide a concise answer to the question.", user="{text}"
    )

    # Initialize optimizer with minimal parameters for faster testing
    optimizer = opik_optimizer.HierarchicalReflectiveOptimizer(
        model="openai/gpt-5-mini",
        model_parameters={
            "temperature": 1,
            "max_tokens": 1000,
            "reasoning_effort": "minimal",
        },
        n_threads=2,
        max_parallel_batches=2,
        batch_size=10,
        convergence_threshold=0.01,
        verbose=1,
        seed=42,
    )

    # Run optimization with minimal trials and samples
    results = optimizer.optimize_prompt(
        prompt=prompt,
        dataset=dataset,
        metric=levenshtein_ratio,
        max_trials=2,
        n_samples=1,
        max_retries=1,
    )

    # Validate OptimizationResult structure
    assert results.optimizer == "HierarchicalReflectiveOptimizer", (
        f"Expected HierarchicalReflectiveOptimizer, got {results.optimizer}"
    )

    # Score validation
    assert isinstance(results.score, (int, float)), (
        f"Score should be numeric, got {type(results.score)}"
    )
    assert 0.0 <= results.score <= 1.0, (
        f"Score should be between 0-1, got {results.score}"
    )

    # Metric name validation
    assert results.metric_name == "levenshtein_ratio", (
        f"Expected levenshtein_ratio, got {results.metric_name}"
    )

    # Initial score validation
    assert isinstance(results.initial_score, (int, float)), (
        f"Initial score should be numeric, got {type(results.initial_score)}"
    )
    assert 0.0 <= results.initial_score <= 1.0, (
        f"Initial score should be between 0-1, got {results.initial_score}"
    )

    # Initial prompt validation
    assert isinstance(results.initial_prompt, list), (
        f"Initial prompt should be a list, got {type(results.initial_prompt)}"
    )
    assert len(results.initial_prompt) > 0, "Initial prompt should not be empty"

    for msg in results.initial_prompt:
        assert isinstance(msg, dict), (
            f"Each initial prompt message should be a dict, got {type(msg)}"
        )
        assert "role" in msg, "Initial prompt message should have 'role' field"
        assert "content" in msg, "Initial prompt message should have 'content' field"
        assert msg["role"] in ["system", "user", "assistant"], (
            f"Invalid role in initial prompt: {msg['role']}"
        )

    # Optimized prompt validation
    assert isinstance(results.prompt, list), (
        f"Prompt should be a list, got {type(results.prompt)}"
    )
    assert len(results.prompt) > 0, "Prompt should not be empty"

    for msg in results.prompt:
        assert isinstance(msg, dict), (
            f"Each prompt message should be a dict, got {type(msg)}"
        )
        assert "role" in msg, "Prompt message should have 'role' field"
        assert "content" in msg, "Prompt message should have 'content' field"
        assert msg["role"] in ["system", "user", "assistant"], (
            f"Invalid role: {msg['role']}"
        )

    # Details validation
    assert isinstance(results.details, dict), (
        f"Details should be a dict, got {type(results.details)}"
    )

    # Validate model configuration in details
    assert "model" in results.details, "Details should contain 'model'"
    assert results.details["model"] == optimizer.model, (
        f"Expected {optimizer.model}, got {results.details['model']}"
    )

    # Validate hierarchical-specific details
    assert "n_threads" in results.details, "Details should contain 'n_threads'"
    assert results.details["n_threads"] == optimizer.n_threads

    assert "max_parallel_batches" in results.details, (
        "Details should contain 'max_parallel_batches'"
    )
    assert results.details["max_parallel_batches"] == 2

    # History validation
    assert isinstance(results.history, list), (
        f"History should be a list, got {type(results.history)}"
    )

    # LLM calls validation
    if results.llm_calls is not None:
        assert isinstance(results.llm_calls, int), (
            f"LLM calls should be int or None, got {type(results.llm_calls)}"
        )
        assert results.llm_calls > 0, (
            f"LLM calls should be positive, got {results.llm_calls}"
        )

    # Test string representation
    result_str = str(results)
    assert isinstance(result_str, str), "String representation should work"
    assert "HierarchicalReflectiveOptimizer" in result_str, (
        "String should contain optimizer name"
    )
    assert "levenshtein_ratio" in result_str, "String should contain metric name"

    # Test model_dump
    result_dict = results.model_dump()
    assert isinstance(result_dict, dict), "model_dump should return dict"
    required_fields = [
        "optimizer",
        "score",
        "metric_name",
        "prompt",
        "initial_prompt",
        "initial_score",
        "details",
        "history",
    ]
    for field in required_fields:
        assert field in result_dict, f"model_dump should contain {field}"


if __name__ == "__main__":
    test_hierarchical_reflective_optimizer()

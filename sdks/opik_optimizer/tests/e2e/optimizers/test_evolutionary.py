import os
import pytest
from opik.evaluation.metrics import LevenshteinRatio
from opik.evaluation.metrics.score_result import ScoreResult
from typing import Any

import opik_optimizer

pytestmark = pytest.mark.integration


def test_evolutionary_optimizer() -> None:
    # Ensure API key is available for e2e testing
    if not os.getenv("OPENAI_API_KEY"):
        pytest.fail("OPENAI_API_KEY environment variable must be set for e2e tests")
    # Prepare dataset (using tiny_test for faster execution)
    dataset = opik_optimizer.datasets.tiny_test()

    # Define metric and task configuration (see docs for more options)
    def levenshtein_ratio(dataset_item: dict[str, Any], llm_output: str) -> ScoreResult:
        return LevenshteinRatio().score(
            reference=dataset_item["label"], output=llm_output
        )

    prompt = opik_optimizer.ChatPrompt(
        system="Provide an answer to the question.", user="{text}"
    )

    # Initialize optimizer with reduced parameters for faster testing
    optimizer = opik_optimizer.EvolutionaryOptimizer(
        model="openai/gpt-5-mini",
        model_parameters={
            "temperature": 1,
            "max_tokens": 1000,
            "reasoning_effort": "minimal",
        },
        infer_output_style=True,
        population_size=2,
        num_generations=2,
        n_threads=2,
        enable_llm_crossover=False,
        enable_moo=False,
        elitism_size=1,
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
    assert results.optimizer == "EvolutionaryOptimizer", (
        f"Expected EvolutionaryOptimizer, got {results.optimizer}"
    )

    assert isinstance(results.score, (int, float)), (
        f"Score should be numeric, got {type(results.score)}"
    )
    assert 0.0 <= results.score <= 1.0, (
        f"Score should be between 0-1, got {results.score}"
    )

    assert results.metric_name == "levenshtein_ratio", (
        f"Expected levenshtein_ratio, got {results.metric_name}"
    )

    # Initial score validation - now a top-level field
    assert isinstance(results.initial_score, (int, float)), (
        f"Initial score should be numeric, got {type(results.initial_score)}"
    )
    assert 0.0 <= results.initial_score <= 1.0, (
        f"Initial score should be between 0-1, got {results.initial_score}"
    )

    # Initial prompt validation - should have same structure as optimized prompt
    assert isinstance(results.initial_prompt, list), (
        f"Initial prompt should be a list, got {type(results.initial_prompt)}"
    )
    assert len(results.initial_prompt) > 0, "Initial prompt should not be empty"

    # Validate initial prompt messages structure
    for msg in results.initial_prompt:
        assert isinstance(msg, dict), (
            f"Each initial prompt message should be a dict, got {type(msg)}"
        )
        assert "role" in msg, "Initial prompt message should have 'role' field"
        assert "content" in msg, "Initial prompt message should have 'content' field"
        assert msg["role"] in [
            "system",
            "user",
            "assistant",
        ], f"Invalid role in initial prompt: {msg['role']}"
        assert isinstance(msg["content"], str), (
            f"Initial prompt content should be string, got {type(msg['content'])}"
        )

    # Optimized prompt structure validation
    assert isinstance(results.prompt, list), (
        f"Prompt should be a list, got {type(results.prompt)}"
    )
    assert len(results.prompt) > 0, "Prompt should not be empty"

    # Validate optimized prompt messages structure
    for msg in results.prompt:
        assert isinstance(msg, dict), (
            f"Each prompt message should be a dict, got {type(msg)}"
        )
        assert "role" in msg, "Prompt message should have 'role' field"
        assert "content" in msg, "Prompt message should have 'content' field"
        assert msg["role"] in [
            "system",
            "user",
            "assistant",
        ], f"Invalid role: {msg['role']}"
        assert isinstance(msg["content"], str), (
            f"Content should be string, got {type(msg['content'])}"
        )

    # Details validation - Evolutionary specific
    assert isinstance(results.details, dict), (
        f"Details should be a dict, got {type(results.details)}"
    )

    # Validate model configuration in details
    assert "model" in results.details, "Details should contain 'model'"
    assert results.details["model"] == optimizer.model, (
        f"Expected {optimizer.model}, got {results.details['model']}"
    )

    assert "temperature" in results.details, "Details should contain 'temperature'"
    assert (
        results.details["temperature"] == optimizer.model_parameters["temperature"]
    ), (
        f"Expected temperature {optimizer.model_parameters['temperature']}, got {results.details['temperature']}"
    )

    # Evolutionary-specific validation
    assert "population_size" in results.details, (
        "Details should contain 'population_size'"
    )
    assert results.details["population_size"] == 2, (
        f"Expected population_size 2, got {results.details['population_size']}"
    )

    assert "num_generations" in results.details, (
        "Details should contain 'num_generations'"
    )
    assert results.details["num_generations"] == 2, (
        f"Expected num_generations 2, got {results.details['num_generations']}"
    )

    # History validation - should contain generation information
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

    # Test result methods work correctly
    result_str = str(results)
    assert isinstance(result_str, str), "String representation should work"
    assert "EvolutionaryOptimizer" in result_str, "String should contain optimizer name"
    assert "levenshtein_ratio" in result_str, "String should contain metric name"

    # Test model dump works
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
    test_evolutionary_optimizer()

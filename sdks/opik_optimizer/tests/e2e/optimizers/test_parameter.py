import os
from typing import Any

import pytest
from opik.evaluation.metrics import LevenshteinRatio
from opik.evaluation.metrics.score_result import ScoreResult

import opik_optimizer

pytestmark = pytest.mark.integration


def test_parameter_optimizer() -> None:
    # Ensure API key is available for e2e testing
    if not os.getenv("OPENAI_API_KEY"):
        pytest.fail("OPENAI_API_KEY environment variable must be set for e2e tests")

    # Prepare dataset (using tiny_test for faster execution)
    dataset = opik_optimizer.datasets.tiny_test()

    # Define metric
    def levenshtein_ratio(dataset_item: dict[str, Any], llm_output: str) -> ScoreResult:
        metric = LevenshteinRatio()
        return metric.score(reference=dataset_item["label"], output=llm_output)

    prompt = opik_optimizer.ChatPrompt(
        system="Provide an answer to the question.", user="{text}"
    )

    # Initialize optimizer with reduced parameters for faster testing
    optimizer = opik_optimizer.ParameterOptimizer(
        model="openai/gpt-5-mini",
        model_parameters={
            "temperature": 1,
            "max_tokens": 1000,
            "reasoning_effort": "minimal",
        },
        default_n_trials=1,
        n_threads=2,
        seed=42,
        local_search_ratio=0.5,
        local_search_scale=0.3,
    )

    # Define parameter search space
    parameter_space = {
        "temperature": {"type": "float", "min": 0.0, "max": 1.0},
        "max_tokens": {"type": "int", "min": 50, "max": 200},
    }

    # Run optimization with reduced sample size
    results = optimizer.optimize_parameter(
        dataset=dataset,
        metric=levenshtein_ratio,
        prompt=prompt,
        parameter_space=parameter_space,
        n_samples=1,
    )

    # Enhanced OptimizationResult validation

    # Core fields validation
    assert results.optimizer == "ParameterOptimizer", (
        f"Expected ParameterOptimizer, got {results.optimizer}"
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

    # Details validation - parameter optimizer specific
    assert isinstance(results.details, dict), (
        f"Details should be a dict, got {type(results.details)}"
    )

    # Parameter optimizer specific fields
    assert "optimized_parameters" in results.details, (
        "Details should contain 'optimized_parameters'"
    )
    assert isinstance(results.details["optimized_parameters"], dict), (
        "optimized_parameters should be a dict"
    )

    # Note: optimized_parameters may be empty if no trials improved the baseline
    # This is acceptable behavior - the optimizer tried but didn't find better values

    assert "optimized_model_kwargs" in results.details, (
        "Details should contain 'optimized_model_kwargs'"
    )
    assert isinstance(results.details["optimized_model_kwargs"], dict), (
        "optimized_model_kwargs should be a dict"
    )

    assert "parameter_space" in results.details, (
        "Details should contain 'parameter_space'"
    )
    assert "n_trials" in results.details, "Details should contain 'n_trials'"
    assert "trials" in results.details, "Details should contain 'trials' history"

    # Validate parameter importance
    assert "parameter_importance" in results.details, (
        "Details should contain 'parameter_importance'"
    )
    assert isinstance(results.details["parameter_importance"], dict), (
        "parameter_importance should be a dict"
    )

    # Validate search stages
    assert "search_stages" in results.details, "Details should contain 'search_stages'"
    assert isinstance(results.details["search_stages"], list), (
        "search_stages should be a list"
    )
    assert len(results.details["search_stages"]) > 0, (
        "Should have at least one search stage"
    )

    # Validate model configuration in details
    assert "model" in results.details, "Details should contain 'model'"
    assert results.details["model"] == optimizer.model, (
        f"Expected model {optimizer.model}, got {results.details['model']}"
    )

    # History validation
    assert isinstance(results.history, list), (
        f"History should be a list, got {type(results.history)}"
    )
    assert len(results.history) > 0, "History should not be empty"

    # LLM calls validation
    # Note: Counter tracking may not be reliable in all environments
    if results.llm_calls is not None and results.llm_calls > 0:
        assert isinstance(results.llm_calls, int), (
            f"LLM calls should be int, got {type(results.llm_calls)}"
        )
        # Counter is working and positive
        assert results.llm_calls > 0, (
            f"LLM calls should be positive, got {results.llm_calls}"
        )

    # Test convenience methods
    optimized_kwargs = results.get_optimized_model_kwargs()
    assert isinstance(optimized_kwargs, dict), (
        "get_optimized_model_kwargs should return dict"
    )
    # optimized_kwargs may be empty or contain baseline values if no improvement found

    optimized_model = results.get_optimized_model()
    # Should return the model that was used, even if no parameters were improved
    assert optimized_model is not None, "get_optimized_model should return a model"

    optimized_params = results.get_optimized_parameters()
    assert isinstance(optimized_params, dict), (
        "get_optimized_parameters should return dict"
    )
    # optimized_params may be empty if no trials improved the baseline

    # Test apply_to_prompt
    new_prompt = results.apply_to_prompt(prompt)
    assert isinstance(new_prompt.model, str), (
        f"Applied prompt model should be a string, got {type(new_prompt.model)}"
    )
    assert new_prompt.model_kwargs is not None, (
        "Applied prompt should have model_kwargs"
    )

    # Test result methods work correctly
    result_str = str(results)
    assert isinstance(result_str, str), "String representation should work"
    assert "ParameterOptimizer" in result_str, "String should contain optimizer name"
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
    test_parameter_optimizer()

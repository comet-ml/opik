import os
import pytest
from opik.evaluation.metrics import LevenshteinRatio
from opik.evaluation.metrics.score_result import ScoreResult
from typing import Any

import opik_optimizer

pytestmark = pytest.mark.integration


def test_few_shot_optimizer() -> None:
    # Ensure API key is available for e2e testing
    if not os.getenv("OPENAI_API_KEY"):
        pytest.fail("OPENAI_API_KEY environment variable must be set for e2e tests")
    # Initialize optimizer
    optimizer = opik_optimizer.FewShotBayesianOptimizer(
        model="openai/gpt-5-mini",
        model_parameters={
            "temperature": 1,
            "max_tokens": 1000,
            "reasoning_effort": "minimal",
        },
        min_examples=1,
        max_examples=2,
    )

    # Prepare dataset (using tiny_test for faster execution)
    dataset = opik_optimizer.datasets.tiny_test()

    # Define metric
    def levenshtein_ratio(dataset_item: dict[str, Any], llm_output: str) -> ScoreResult:
        return LevenshteinRatio().score(
            reference=dataset_item["label"], output=llm_output
        )

    # Updated ChatPrompt for tiny_test dataset field names
    prompt = opik_optimizer.ChatPrompt(
        messages=[
            {"role": "system", "content": "Provide an answer to the question."},
            {"role": "user", "content": "{text}"},
        ]
    )

    # Run optimization with reduced parameters for faster testing
    results = optimizer.optimize_prompt(
        dataset=dataset,
        metric=levenshtein_ratio,
        prompt=prompt,
        max_trials=1,
        n_samples=1,
    )

    # Enhanced OptimizationResult validation

    # Core fields validation - focus on values, not existence
    assert results.optimizer == "FewShotBayesianOptimizer", (
        f"Expected FewShotBayesianOptimizer, got {results.optimizer}"
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

    # Initial prompt validation - should be ChatPrompt or dict[str, ChatPrompt]
    from opik_optimizer.api_objects.chat_prompt import ChatPrompt
    
    assert results.initial_prompt is not None, "Initial prompt should not be None"
    
    # Handle both single ChatPrompt and dict of ChatPrompts for initial_prompt
    if isinstance(results.initial_prompt, dict):
        # Dictionary of ChatPrompts
        assert len(results.initial_prompt) > 0, "Initial prompt dict should not be empty"
        for key, chat_prompt in results.initial_prompt.items():
            assert isinstance(chat_prompt, ChatPrompt), (
                f"Each initial prompt value should be a ChatPrompt, got {type(chat_prompt)}"
            )
            messages = chat_prompt.get_messages()
            assert len(messages) > 0, f"Initial prompt [{key}] should have messages"
            for msg in messages:
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
    else:
        # Single ChatPrompt
        assert isinstance(results.initial_prompt, ChatPrompt), (
            f"Initial prompt should be a ChatPrompt, got {type(results.initial_prompt)}"
        )
        messages = results.initial_prompt.get_messages()
        assert len(messages) > 0, "Initial prompt should have messages"
        for msg in messages:
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

    # Optimized prompt structure validation - should be ChatPrompt or dict[str, ChatPrompt]
    # Also validate that optimized prompts don't contain the few-shot placeholder string
    from opik_optimizer.algorithms.few_shot_bayesian_optimizer.prompts import (
        FEW_SHOT_EXAMPLE_PLACEHOLDER,
    )
    
    if isinstance(results.prompt, dict):
        # Dictionary of ChatPrompts
        assert len(results.prompt) > 0, "Prompt dict should not be empty"
        for key, chat_prompt in results.prompt.items():
            assert isinstance(chat_prompt, ChatPrompt), (
                f"Each prompt value should be a ChatPrompt, got {type(chat_prompt)}"
            )
            messages = chat_prompt.get_messages()
            assert len(messages) > 0, f"Prompt [{key}] should have messages"
            for msg in messages:
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
                
                # Check that the few-shot placeholder was replaced
                content = msg["content"]
                if isinstance(content, str):
                    assert FEW_SHOT_EXAMPLE_PLACEHOLDER not in content, (
                        f"Optimized prompt [{key}] still contains placeholder '{FEW_SHOT_EXAMPLE_PLACEHOLDER}' "
                        f"in {msg['role']} message. The placeholder should be replaced with actual examples."
                    )
    else:
        # Single ChatPrompt
        assert isinstance(results.prompt, ChatPrompt), (
            f"Prompt should be a ChatPrompt, got {type(results.prompt)}"
        )
        messages = results.prompt.get_messages()
        assert len(messages) > 0, "Prompt should have messages"
        for msg in messages:
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
            
            # Check that the few-shot placeholder was replaced
            content = msg["content"]
            if isinstance(content, str):
                assert FEW_SHOT_EXAMPLE_PLACEHOLDER not in content, (
                    f"Optimized prompt still contains placeholder '{FEW_SHOT_EXAMPLE_PLACEHOLDER}' "
                    f"in {msg['role']} message. The placeholder should be replaced with actual examples."
                )

    # Details validation - FewShot specific
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

    # Optional fields that might not be in actual results structure
    if "min_examples" in results.details:
        assert results.details["min_examples"] == 1, (
            f"Expected min_examples 1, got {results.details['min_examples']}"
        )

    if "max_examples" in results.details:
        assert results.details["max_examples"] == 2, (
            f"Expected max_examples 2, got {results.details['max_examples']}"
        )

    # History validation - FewShot uses history for optimization steps
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
    assert "FewShotBayesianOptimizer" in result_str, (
        "String should contain optimizer name"
    )
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
    test_few_shot_optimizer()

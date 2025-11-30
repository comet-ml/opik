import os
import pytest
from opik.evaluation.metrics import LevenshteinRatio
from opik.evaluation.metrics.score_result import ScoreResult
from typing import Any

import opik_optimizer

pytestmark = pytest.mark.integration


def test_metaprompt_optimizer() -> None:
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
    optimizer = opik_optimizer.MetaPromptOptimizer(
        model="openai/gpt-5-mini",
        model_parameters={
            "temperature": 1,
            "max_tokens": 1000,
            "reasoning_effort": "minimal",
        },
        n_threads=2,
        prompts_per_round=1,
        seed=42,
    )

    # Run optimization with reduced sample size
    results = optimizer.optimize_prompt(
        dataset=dataset,
        metric=levenshtein_ratio,
        prompt=prompt,
        n_samples=1,
        max_trials=1,
    )

    # Enhanced OptimizationResult validation

    # Core fields validation - focus on values, not existence
    assert results.optimizer == "MetaPromptOptimizer", (
        f"Expected MetaPromptOptimizer, got {results.optimizer}"
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
    assert results.initial_prompt is not None, "Initial prompt should not be None"
    
    if isinstance(results.initial_prompt, dict):
        # Dictionary of ChatPrompts
        assert len(results.initial_prompt) > 0, "Initial prompt dict should not be empty"
        for key, chat_p in results.initial_prompt.items():
            assert isinstance(key, str), f"Prompt key should be string, got {type(key)}"
            assert isinstance(chat_p, opik_optimizer.ChatPrompt), (
                f"Each initial prompt value should be ChatPrompt, got {type(chat_p)}"
            )
            messages = chat_p.get_messages()
            assert len(messages) > 0, f"Initial prompt [{key}] should have messages"
            for msg in messages:
                assert "role" in msg, f"Initial prompt [{key}] message should have 'role'"
                assert "content" in msg, f"Initial prompt [{key}] message should have 'content'"
    else:
        # Single ChatPrompt
        assert isinstance(results.initial_prompt, opik_optimizer.ChatPrompt), (
            f"Initial prompt should be ChatPrompt, got {type(results.initial_prompt)}"
        )
        messages = results.initial_prompt.get_messages()
        assert len(messages) > 0, "Initial prompt should have messages"
        for msg in messages:
            assert "role" in msg, "Initial prompt message should have 'role' field"
            assert "content" in msg, "Initial prompt message should have 'content' field"

    # Optimized prompt structure validation - should be ChatPrompt or dict[str, ChatPrompt]
    if isinstance(results.prompt, dict):
        # Dictionary of ChatPrompts
        assert len(results.prompt) > 0, "Prompt dict should not be empty"
        for key, chat_p in results.prompt.items():
            assert isinstance(key, str), f"Prompt key should be string, got {type(key)}"
            assert isinstance(chat_p, opik_optimizer.ChatPrompt), (
                f"Each prompt value should be ChatPrompt, got {type(chat_p)}"
            )
            messages = chat_p.get_messages()
            assert len(messages) > 0, f"Prompt [{key}] should have messages"
            for msg in messages:
                assert "role" in msg, f"Prompt [{key}] message should have 'role'"
                assert "content" in msg, f"Prompt [{key}] message should have 'content'"
    else:
        # Single ChatPrompt
        assert isinstance(results.prompt, opik_optimizer.ChatPrompt), (
            f"Prompt should be ChatPrompt, got {type(results.prompt)}"
        )
        messages = results.prompt.get_messages()
        assert len(messages) > 0, "Prompt should have messages"
        for msg in messages:
            assert "role" in msg, "Prompt message should have 'role' field"
            assert "content" in msg, "Prompt message should have 'content' field"

    # Details validation
    assert isinstance(results.details, dict), (
        f"Details should be a dict, got {type(results.details)}"
    )
    assert "rounds" in results.details, "Details should contain 'rounds'"
    assert len(results.details["rounds"]) > 0, (
        "Should have at least one optimization round"
    )

    # History validation - MetaPrompt uses details['rounds'] instead of history
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
    assert "MetaPromptOptimizer" in result_str, "String should contain optimizer name"
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
    test_metaprompt_optimizer()

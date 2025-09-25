import os
import pytest
from opik.evaluation.metrics import LevenshteinRatio
from opik.evaluation.metrics.score_result import ScoreResult
from typing import Any

from opik_optimizer import TaskConfig, datasets
from opik_optimizer.mipro_optimizer import MiproOptimizer


def test_mipro_optimizer() -> None:
    # Ensure API key is available for e2e testing
    if not os.getenv("OPENAI_API_KEY"):
        pytest.fail("OPENAI_API_KEY environment variable must be set for e2e tests")
    # Initialize optimizer
    optimizer = MiproOptimizer(
        model="openai/gpt-4o",
        project_name="mipro_optimization_project",
        temperature=0.1,
        max_tokens=5000,
    )

    # Prepare dataset (using tiny_test for faster execution)
    dataset = datasets.tiny_test()

    # Define metric and task configuration
    def levenshtein_ratio(dataset_item: dict[str, Any], llm_output: str) -> ScoreResult:
        return LevenshteinRatio().score(
            reference=dataset_item["label"], output=llm_output
        )

    # Define some tools
    def calculator(expression: str) -> str:
        """Perform mathematical calculations"""
        return str(eval(expression))

    def search(query: str) -> str:
        """Search for information on a given topic"""
        # placeholder for search functionality
        return "hello_world"

    # Define task configuration with tools (updated field names for tiny_test dataset)
    task_config = TaskConfig(
        instruction_prompt="Complete the task using the provided tools.",
        input_dataset_fields=["text"],  # Changed from ["question"]
        output_dataset_field="label",  # Changed from "answer"
        use_chat_prompt=True,
        tools=[search, calculator],
    )

    # Create a dummy prompt for the standardized API
    from opik_optimizer.optimization_config.chat_prompt import ChatPrompt

    dummy_prompt = ChatPrompt(
        messages=[
            {"role": "system", "content": "You are a helpful assistant."},
            {"role": "user", "content": "Complete the task using the provided tools."},
        ]
    )

    # Run optimization with reduced parameters for faster testing
    results = optimizer.optimize_prompt(
        prompt=dummy_prompt,
        dataset=dataset,
        metric=levenshtein_ratio,
        task_config=task_config,
        num_threads=4,
        num_candidates=1,
        num_trials=1,  # Reduced from 2
        n_samples=3,  # Added to prevent sampling more than dataset size
    )

    # Enhanced OptimizationResult validation

    # Core fields validation - focus on values, not existence
    assert results.optimizer == "MiproOptimizer", (
        f"Expected MiproOptimizer, got {results.optimizer}"
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
    assert results.initial_score is None, "Initial score should be None"

    # Initial prompt validation - should have same structure as optimized prompt
    assert results.initial_prompt is None, "Initial prompt should be empty"

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

    # Details validation - Mipro specific
    assert isinstance(results.details, dict), (
        f"Details should be a dict, got {type(results.details)}"
    )

    # Validate model configuration in details (if present)
    if "model" in results.details:
        assert results.details["model"] == "openai/gpt-4", (
            f"Expected openai/gpt-4, got {results.details['model']}"
        )

    if "temperature" in results.details:
        assert results.details["temperature"] == 0.1, (
            f"Expected temperature 0.1, got {results.details['temperature']}"
        )

    # Mipro-specific validation - flexible checking for available fields
    if "mipro_prompt" in results.details:
        mipro_prompt = results.details["mipro_prompt"]
        assert isinstance(mipro_prompt, (str, dict, list)), (
            f"Mipro prompt should be str/dict/list, got {type(mipro_prompt)}"
        )

    if "tool_prompts" in results.details:
        tool_prompts = results.details["tool_prompts"]
        assert isinstance(tool_prompts, (dict, list)), (
            f"Tool prompts should be dict/list, got {type(tool_prompts)}"
        )

    # History validation - Mipro uses history for optimization steps
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
    assert "MiproOptimizer" in result_str, "String should contain optimizer name"
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
    test_mipro_optimizer()

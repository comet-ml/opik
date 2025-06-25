from opik.evaluation.metrics import LevenshteinRatio
from opik.evaluation.metrics.score_result import ScoreResult
from typing import Any, Dict

from opik_optimizer import FewShotBayesianOptimizer, datasets
from opik_optimizer.optimization_config import chat_prompt


def test_few_shot_optimizer() -> None:
    # Initialize optimizer
    optimizer = FewShotBayesianOptimizer(
        model="openai/gpt-4",
        temperature=0.1,
        max_tokens=5000,
        min_examples=1,
        max_examples=2,
    )

    # Prepare dataset (using tiny_test for faster execution)
    dataset = datasets.tiny_test()

    # Define metric
    def levenshtein_ratio(dataset_item: Dict[str, Any], llm_output: str) -> ScoreResult:
        return LevenshteinRatio().score(
            reference=dataset_item["label"], output=llm_output
        )

    # Updated ChatPrompt for tiny_test dataset field names
    prompt = chat_prompt.ChatPrompt(
        messages=[
            {"role": "system", "content": "Provide an answer to the question."},
            {"role": "user", "content": "{text}"},  # Changed from "{question}"
        ],
    )

    # Run optimization with reduced parameters for faster testing
    results = optimizer.optimize_prompt(
        dataset=dataset,
        metric=levenshtein_ratio,
        prompt=prompt,
        n_trials=1,  # Reduced from 2
    )

    # Enhanced OptimizationResult validation

    # Core fields validation - focus on values, not existence
    assert (
        results.optimizer == "FewShotBayesianOptimizer"
    ), f"Expected FewShotBayesianOptimizer, got {results.optimizer}"

    assert isinstance(
        results.score, (int, float)
    ), f"Score should be numeric, got {type(results.score)}"
    assert (
        0.0 <= results.score <= 1.0
    ), f"Score should be between 0-1, got {results.score}"

    assert (
        results.metric_name == "levenshtein_ratio"
    ), f"Expected levenshtein_ratio, got {results.metric_name}"

    # Initial score validation - now a top-level field
    assert isinstance(
        results.initial_score, (int, float)
    ), f"Initial score should be numeric, got {type(results.initial_score)}"
    assert (
        0.0 <= results.initial_score <= 1.0
    ), f"Initial score should be between 0-1, got {results.initial_score}"

    # Initial prompt validation - should have same structure as optimized prompt
    assert isinstance(
        results.initial_prompt, list
    ), f"Initial prompt should be a list, got {type(results.initial_prompt)}"
    assert len(results.initial_prompt) > 0, "Initial prompt should not be empty"

    # Validate initial prompt messages structure
    for msg in results.initial_prompt:
        assert isinstance(
            msg, dict
        ), f"Each initial prompt message should be a dict, got {type(msg)}"
        assert "role" in msg, "Initial prompt message should have 'role' field"
        assert "content" in msg, "Initial prompt message should have 'content' field"
        assert msg["role"] in [
            "system",
            "user",
            "assistant",
        ], f"Invalid role in initial prompt: {msg['role']}"
        assert isinstance(
            msg["content"], str
        ), f"Initial prompt content should be string, got {type(msg['content'])}"

    # Optimized prompt structure validation
    assert isinstance(
        results.prompt, list
    ), f"Prompt should be a list, got {type(results.prompt)}"
    assert len(results.prompt) > 0, "Prompt should not be empty"

    # Validate optimized prompt messages structure
    for msg in results.prompt:
        assert isinstance(
            msg, dict
        ), f"Each prompt message should be a dict, got {type(msg)}"
        assert "role" in msg, "Prompt message should have 'role' field"
        assert "content" in msg, "Prompt message should have 'content' field"
        assert msg["role"] in [
            "system",
            "user",
            "assistant",
        ], f"Invalid role: {msg['role']}"
        assert isinstance(
            msg["content"], str
        ), f"Content should be string, got {type(msg['content'])}"

    # Details validation - FewShot specific
    assert isinstance(
        results.details, dict
    ), f"Details should be a dict, got {type(results.details)}"

    # Validate model configuration in details
    assert "model" in results.details, "Details should contain 'model'"
    assert (
        results.details["model"] == "openai/gpt-4"
    ), f"Expected openai/gpt-4, got {results.details['model']}"

    assert "temperature" in results.details, "Details should contain 'temperature'"
    assert (
        results.details["temperature"] == 0.1
    ), f"Expected temperature 0.1, got {results.details['temperature']}"

    # Optional fields that might not be in actual results structure
    if "min_examples" in results.details:
        assert (
            results.details["min_examples"] == 1
        ), f"Expected min_examples 1, got {results.details['min_examples']}"

    if "max_examples" in results.details:
        assert (
            results.details["max_examples"] == 2
        ), f"Expected max_examples 2, got {results.details['max_examples']}"

    # History validation - FewShot uses history for optimization steps
    assert isinstance(
        results.history, list
    ), f"History should be a list, got {type(results.history)}"

    # LLM calls validation
    if results.llm_calls is not None:
        assert isinstance(
            results.llm_calls, int
        ), f"LLM calls should be int or None, got {type(results.llm_calls)}"
        assert (
            results.llm_calls > 0
        ), f"LLM calls should be positive, got {results.llm_calls}"

    # Test result methods work correctly
    result_str = str(results)
    assert isinstance(result_str, str), "String representation should work"
    assert (
        "FewShotBayesianOptimizer" in result_str
    ), "String should contain optimizer name"
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

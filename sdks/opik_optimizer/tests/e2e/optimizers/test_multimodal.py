import os
import pytest
from opik.evaluation.metrics import LevenshteinRatio
from opik.evaluation.metrics.score_result import ScoreResult
from typing import Any

import opik_optimizer

pytestmark = pytest.mark.integration


def test_multimodal_hierarchical_reflective_optimizer() -> None:
    """E2E test for multimodal support with HierarchicalReflectiveOptimizer."""
    # Ensure API key is available for e2e testing
    if not os.getenv("OPENAI_API_KEY"):
        pytest.fail("OPENAI_API_KEY environment variable must be set for e2e tests")

    # Load multimodal dataset (driving hazard detection)
    dataset = opik_optimizer.datasets.driving_hazard(test_mode=True)

    # Define metric with reason field for hierarchical analysis
    def levenshtein_ratio(dataset_item: dict[str, Any], llm_output: str) -> ScoreResult:
        metric = LevenshteinRatio()
        metric_score = metric.score(
            reference=dataset_item.get("hazard") or dataset_item.get("label"),
            output=llm_output,
        )
        return ScoreResult(
            value=metric_score.value,
            name=metric_score.name,
            reason=f"Levenshtein ratio between `{dataset_item.get('hazard') or dataset_item.get('label')}` and `{llm_output}` is `{metric_score.value}`.",
        )

    # Create multimodal prompt with structured content (text + image)
    system_prompt = """You are an expert driving safety assistant specialized in hazard detection.

Your task is to analyze dashcam images and identify potential hazards that a driver should be aware of.

For each image:
1. Carefully examine the visual scene
2. Identify any potential hazards (pedestrians, vehicles, road conditions, obstacles, etc.)
3. Assess the urgency and severity of each hazard
4. Provide a clear, specific description of the hazard

Be precise and actionable in your hazard descriptions. Focus on safety-critical information."""

    prompt = opik_optimizer.ChatPrompt(
        messages=[
            {"role": "system", "content": system_prompt},
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": "{question}"},
                    {
                        "type": "image_url",
                        "image_url": {
                            "url": "{image}",
                        },
                    },
                ],
            },
        ],
    )

    # Initialize optimizer with minimal parameters for faster testing
    optimizer = opik_optimizer.HierarchicalReflectiveOptimizer(
        model="openai/gpt-5-mini",
        model_parameters={
            "temperature": 1,
            "reasoning_effort": "minimal",
        },
        n_threads=1,
        max_parallel_batches=2,
        batch_size=2,
        convergence_threshold=0.01,
        verbose=1,
        seed=42,
    )

    # Run optimization with minimal trials for testing
    results = optimizer.optimize_prompt(
        prompt=prompt,
        dataset=dataset,
        metric=levenshtein_ratio,
        max_trials=2,
        n_samples=2,
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

    # Initial prompt validation - should contain multimodal content
    assert isinstance(results.initial_prompt, list), (
        f"Initial prompt should be a list, got {type(results.initial_prompt)}"
    )
    assert len(results.initial_prompt) > 0, "Initial prompt should not be empty"

    # Validate initial prompt has multimodal structure
    has_multimodal_content = False
    for msg in results.initial_prompt:
        assert isinstance(msg, dict), (
            f"Each initial prompt message should be a dict, got {type(msg)}"
        )
        assert "role" in msg, "Initial prompt message should have 'role' field"
        assert "content" in msg, "Initial prompt message should have 'content' field"
        assert msg["role"] in ["system", "user", "assistant"], (
            f"Invalid role in initial prompt: {msg['role']}"
        )

        # Check for multimodal content structure
        if isinstance(msg["content"], list):
            has_multimodal_content = True
            # Validate structured content format
            for content_part in msg["content"]:
                assert isinstance(content_part, dict), (
                    f"Content part should be dict, got {type(content_part)}"
                )
                assert "type" in content_part, "Content part should have 'type' field"
                assert content_part["type"] in ["text", "image_url"], (
                    f"Invalid content type: {content_part['type']}"
                )

                if content_part["type"] == "image_url":
                    assert "image_url" in content_part, (
                        "Image content should have 'image_url' field"
                    )
                    assert isinstance(content_part["image_url"], dict), (
                        "image_url should be a dict"
                    )
                    assert "url" in content_part["image_url"], (
                        "image_url should have 'url' field"
                    )

    assert has_multimodal_content, (
        "Initial prompt should contain multimodal structured content"
    )

    # Optimized prompt validation - should maintain multimodal structure
    assert isinstance(results.prompt, list), (
        f"Prompt should be a list, got {type(results.prompt)}"
    )
    assert len(results.prompt) > 0, "Prompt should not be empty"

    # Validate optimized prompt maintains multimodal content
    has_optimized_multimodal_content = False
    for msg in results.prompt:
        assert isinstance(msg, dict), (
            f"Each prompt message should be a dict, got {type(msg)}"
        )
        assert "role" in msg, "Prompt message should have 'role' field"
        assert "content" in msg, "Prompt message should have 'content' field"
        assert msg["role"] in ["system", "user", "assistant"], (
            f"Invalid role: {msg['role']}"
        )

        # Check for multimodal content structure in optimized prompt
        if isinstance(msg["content"], list):
            has_optimized_multimodal_content = True
            for content_part in msg["content"]:
                assert isinstance(content_part, dict), (
                    f"Content part should be dict, got {type(content_part)}"
                )
                assert "type" in content_part, "Content part should have 'type' field"

    assert has_optimized_multimodal_content, (
        "Optimized prompt should preserve multimodal structured content"
    )

    # Details validation
    assert isinstance(results.details, dict), (
        f"Details should be a dict, got {type(results.details)}"
    )

    # Validate model configuration
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
    test_multimodal_hierarchical_reflective_optimizer()

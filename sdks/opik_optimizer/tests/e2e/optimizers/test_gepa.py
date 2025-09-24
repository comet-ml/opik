import os
import pytest
from opik.evaluation.metrics import LevenshteinRatio
from opik.evaluation.metrics.score_result import ScoreResult
from typing import Any

# Ensure gepa package is available - fail if not installed
try:
    import gepa  # type: ignore[import-untyped]
except ImportError:
    pytest.fail("gepa package is required for GEPA optimizer tests. Install with: pip install 'opik_optimizer[dev]'")

from opik_optimizer import (
    GepaOptimizer,
    datasets,
)
from opik_optimizer.optimization_config import chat_prompt


def test_gepa_optimizer() -> None:
    # Ensure API key is available for e2e testing
    if not os.getenv("OPENAI_API_KEY"):
        pytest.fail("OPENAI_API_KEY environment variable must be set for e2e tests")
    
    # Prepare dataset (using tiny_test for faster execution)
    dataset = datasets.tiny_test()

    # Define metric and task configuration (see docs for more options)
    def levenshtein_ratio(dataset_item: dict[str, Any], llm_output: str) -> ScoreResult:
        metric = LevenshteinRatio()
        return metric.score(reference=dataset_item["label"], output=llm_output)

    prompt = chat_prompt.ChatPrompt(
        system="Provide an answer to the question.", user="{text}"
    )

    # Initialize optimizer with reduced parameters for faster testing
    optimizer = GepaOptimizer(
        model="openai/gpt-4o-mini",
        reflection_model="openai/gpt-4o-mini",
        temperature=0.1,
        max_tokens=1000,
        num_threads=2,
        rounds=1,
        num_prompts_per_round=2,
        seed=42,
    )

    # Run optimization with reduced sample size
    results = optimizer.optimize_prompt(
        dataset=dataset, metric=levenshtein_ratio, prompt=prompt, n_samples=3
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

    # Results structure validation
    assert hasattr(results, "best_prompt"), "Results should have best_prompt attribute"
    assert hasattr(results, "best_score"), "Results should have best_score attribute"
    assert hasattr(results, "optimization_history"), "Results should have optimization_history attribute"

    # Best prompt validation
    best_prompt = results.best_prompt
    assert best_prompt is not None, "Best prompt should not be None"
    assert isinstance(best_prompt, chat_prompt.ChatPrompt), (
        f"Best prompt should be ChatPrompt instance, got {type(best_prompt)}"
    )

    # Best score validation
    best_score = results.best_score
    assert best_score is not None, "Best score should not be None"
    assert isinstance(best_score, (int, float)), (
        f"Best score should be numeric, got {type(best_score)}"
    )
    assert 0 <= best_score <= 1, f"Best score should be between 0 and 1, got {best_score}"

    # Optimization history validation
    history = results.optimization_history
    assert isinstance(history, list), f"History should be list, got {type(history)}"
    assert len(history) > 0, "History should not be empty"

    # Validate history entries
    for i, entry in enumerate(history):
        assert isinstance(entry, dict), f"History entry {i} should be dict, got {type(entry)}"
        assert "prompt" in entry, f"History entry {i} should have 'prompt' key"
        assert "score" in entry, f"History entry {i} should have 'score' key"
        assert isinstance(entry["score"], (int, float)), (
            f"History entry {i} score should be numeric, got {type(entry['score'])}"
        )

    # Validate that best score matches the highest score in history
    max_history_score = max(entry["score"] for entry in history)
    assert best_score == max_history_score, (
        f"Best score {best_score} should match max history score {max_history_score}"
    )

    # Validate that the best prompt is in the history
    best_prompt_in_history = any(
        entry["prompt"] == best_prompt for entry in history
    )
    assert best_prompt_in_history, "Best prompt should be present in optimization history"

    print(f"✅ GEPA optimization completed successfully!")
    print(f"   Best score: {best_score:.4f}")
    print(f"   History entries: {len(history)}")
    print(f"   Best prompt system: {best_prompt.system[:100]}...")

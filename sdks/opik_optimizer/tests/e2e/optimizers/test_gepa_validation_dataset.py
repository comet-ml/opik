"""
Test to verify GEPA optimizer correctly handles validation_dataset parameter.

This test reproduces issue #4447 where OpikGEPAAdapter only receives the training
dataset, but GEPA passes items from valset (validation) to adapter.evaluate().
The adapter can't find validation item IDs in the training dataset.

See: https://github.com/comet-ml/opik/issues/4447
"""

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

import opik
import opik_optimizer

pytestmark = pytest.mark.integration


def test_gepa_optimizer_with_validation_dataset() -> None:
    """
    Test that GEPA optimizer correctly handles a separate validation dataset.

    This test creates two separate datasets (train and validation) with different
    items and verifies that GEPA can evaluate candidates against validation items
    without ID mismatch errors.
    """
    # Ensure API key is available for e2e testing
    if not os.getenv("OPENAI_API_KEY"):
        pytest.fail("OPENAI_API_KEY environment variable must be set for e2e tests")

    client = opik.Opik()

    # Create training dataset with specific items
    train_dataset_name = "gepa_validation_test_train"
    train_records = [
        {"text": "What is 2 + 2?", "label": "4"},
        {"text": "What is the capital of France?", "label": "Paris"},
        {"text": "What color is the sky?", "label": "Blue"},
    ]

    # Create validation dataset with DIFFERENT items
    val_dataset_name = "gepa_validation_test_val"
    val_records = [
        {"text": "What is 3 + 3?", "label": "6"},
        {"text": "What is the capital of Germany?", "label": "Berlin"},
        {"text": "What color is grass?", "label": "Green"},
    ]

    # Create fresh datasets
    train_dataset = client.get_or_create_dataset(train_dataset_name)
    train_dataset.insert(train_records)

    val_dataset = client.get_or_create_dataset(val_dataset_name)
    val_dataset.insert(val_records)

    # Define metric
    def levenshtein_ratio(dataset_item: dict[str, Any], llm_output: str) -> ScoreResult:
        metric = LevenshteinRatio()
        return metric.score(reference=dataset_item["label"], output=llm_output)

    prompt = opik_optimizer.ChatPrompt(
        system="Provide a brief answer to the question.", user="{text}"
    )

    # Initialize optimizer
    optimizer = opik_optimizer.GepaOptimizer(
        model="openai/gpt-4o-mini",
        model_parameters={
            "temperature": 0.7,
            "max_tokens": 100,
        },
        n_threads=2,
        seed=42,
    )

    try:
        # Run optimization with separate validation dataset
        # This should NOT produce warnings about missing dataset_item_ids
        results = optimizer.optimize_prompt(
            dataset=train_dataset,
            validation_dataset=val_dataset,  # Separate validation dataset
            metric=levenshtein_ratio,
            prompt=prompt,
            n_samples=3,
            max_trials=2,
        )

        # Basic validation that optimization completed
        assert results.optimizer == "GepaOptimizer"
        assert results.prompt is not None
        assert len(results.prompt) > 0

        print("✅ GEPA optimization with validation_dataset completed successfully!")
        print(f"   Final score: {results.score}")

    finally:
        # Cleanup: delete test datasets
        try:
            train_dataset.delete()
        except Exception:
            pass
        try:
            val_dataset.delete()
        except Exception:
            pass

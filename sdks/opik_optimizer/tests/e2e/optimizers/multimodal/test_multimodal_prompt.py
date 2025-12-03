"""
E2E tests for multimodal prompt optimization with text and images.

Tests verify that all prompt-focused optimizers:
1. Complete optimization with multimodal content
2. Preserve multimodal structure (content parts)
3. Preserve image URL placeholders
4. Allow text content to be optimized

Note: Some optimizers are expected to fail initially as they don't yet
support content_parts. These will be updated to support multimodal prompts.
"""

import os
import copy

import pytest
from typing import Any

from opik.evaluation.metrics import LevenshteinRatio
from opik.evaluation.metrics.score_result import ScoreResult

import opik_optimizer
from opik_optimizer import (
    ChatPrompt,
    EvolutionaryOptimizer,
    MetaPromptOptimizer,
    FewShotBayesianOptimizer,
    GepaOptimizer,
    HierarchicalReflectiveOptimizer,
)


pytestmark = pytest.mark.integration


# -----------------------------------------------------------------------------
# Helpers
# -----------------------------------------------------------------------------


def get_driving_hazard_dataset():
    """Get the driving hazard dataset for multimodal testing."""
    return opik_optimizer.datasets.driving_hazard(test_mode=True)


def hazard_metric(dataset_item: dict[str, Any], llm_output: str) -> ScoreResult:
    """Metric for driving hazard detection."""
    metric = LevenshteinRatio()
    reference = dataset_item.get("hazard") or dataset_item.get("label")
    return metric.score(reference=reference, output=llm_output)


def create_optimizer_config(optimizer_class: type) -> dict[str, Any]:
    """
    Create minimal optimizer configuration for fast testing.
    """
    base_config = {
        "model": "openai/gpt-4o-mini",
        "model_parameters": {
            "temperature": 0.7,
            "max_tokens": 500,
        },
        "verbose": 0,
        "seed": 42,
    }
    
    optimizer_specific = {
        EvolutionaryOptimizer: {
            "population_size": 2,
            "num_generations": 1,
            "n_threads": 2,
            "enable_llm_crossover": False,
            "enable_moo": False,
            "elitism_size": 1,
        },
        MetaPromptOptimizer: {
            "n_threads": 2,
            "prompts_per_round": 1,
        },
        FewShotBayesianOptimizer: {
            "min_examples": 1,
            "max_examples": 2,
        },
        GepaOptimizer: {
            "n_threads": 2,
        },
        HierarchicalReflectiveOptimizer: {
            "n_threads": 2,
            "max_parallel_batches": 2,
            "batch_size": 2,
            "convergence_threshold": 0.01,
        },
    }
    
    return {**base_config, **optimizer_specific.get(optimizer_class, {})}


def create_multimodal_prompt() -> ChatPrompt:
    """Create a multimodal ChatPrompt with text and image content."""
    system_prompt = (
        "You are an image analysis assistant. Describe what you see "
        "in the image and answer any questions about it."
    )
    return ChatPrompt(
        name="multimodal-prompt",
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
        model="openai/gpt-4o-mini",
    )


# -----------------------------------------------------------------------------
# Tests
# -----------------------------------------------------------------------------


@pytest.mark.parametrize("optimizer_class", [
    EvolutionaryOptimizer,
    MetaPromptOptimizer,
    FewShotBayesianOptimizer,
    GepaOptimizer,
    HierarchicalReflectiveOptimizer,
])
def test_multimodal_prompt(optimizer_class: type) -> None:
    """
    Test that optimizers can handle multimodal prompts with text and images.
    
    This test verifies:
    1. Optimization completes with multimodal content
    2. Multimodal structure (content parts) is preserved
    3. Image URL placeholders are preserved
    4. Text content can be optimized
    
    Note: MetaPrompt, FewShot, and GEPA are expected to fail initially
    as they don't yet support content_parts. These will be updated.
    """
    # Skip if no API key
    if not os.getenv("OPENAI_API_KEY"):
        pytest.skip("OPENAI_API_KEY environment variable required")
    
    # Skip GEPA if not installed
    if optimizer_class == GepaOptimizer:
        import importlib.util
        if importlib.util.find_spec("gepa") is None:
            pytest.skip("gepa package not installed")
    
    # Create multimodal prompt
    original_prompt = create_multimodal_prompt()
    
    # Keep copy for comparison
    original_messages = copy.deepcopy(original_prompt.get_messages())
    
    # Get multimodal dataset
    dataset = get_driving_hazard_dataset()
    
    # Create optimizer with minimal config
    config = create_optimizer_config(optimizer_class)
    optimizer = optimizer_class(**config)
    
    # Run optimization
    results = optimizer.optimize_prompt(
        dataset=dataset,
        metric=hazard_metric,
        prompt=original_prompt,
        n_samples=2,
        max_trials=2,
    )
    
    # Validate results structure
    assert results.optimizer == optimizer_class.__name__, (
        f"Expected {optimizer_class.__name__}, got {results.optimizer}"
    )
    
    # Get optimized prompt - handle both ChatPrompt and list returns
    optimized_prompt = results.prompt
    
    if isinstance(optimized_prompt, ChatPrompt):
        optimized_messages = optimized_prompt.get_messages()
    elif isinstance(optimized_prompt, list):
        optimized_messages = optimized_prompt
    else:
        pytest.fail(f"Unexpected prompt type: {type(optimized_prompt)}")
    
    assert len(optimized_messages) > 0, "Optimized prompt should have messages"
    
    # Verify multimodal structure is preserved
    has_multimodal_content = False
    for i, msg in enumerate(optimized_messages):
        assert isinstance(msg, dict), f"Message {i} should be dict"
        assert "role" in msg, f"Message {i} should have 'role'"
        assert "content" in msg, f"Message {i} should have 'content'"
        
        content = msg.get("content")
        if isinstance(content, list):
            has_multimodal_content = True
            
            # Verify content parts structure
            for part in content:
                assert isinstance(part, dict), "Content part should be dict"
                assert "type" in part, "Content part should have 'type'"
                
                if part["type"] == "image_url":
                    assert "image_url" in part, "Image part should have 'image_url'"
                    assert isinstance(part["image_url"], dict), "image_url should be dict"
                    assert "url" in part["image_url"], "image_url should have 'url'"
                    
                    # Verify placeholder is preserved
                    url = part["image_url"]["url"]
                    assert "{" in url and "}" in url, (
                        f"Image URL placeholder not preserved: {url}"
                    )
    
    assert has_multimodal_content, (
        "Optimized prompt should preserve multimodal content structure. "
        "The optimizer may not support content_parts yet."
    )
    
    print(f"✅ {optimizer_class.__name__}: Multimodal prompt - PASSED")


# -----------------------------------------------------------------------------
# Individual Optimizer Tests (for targeted debugging)
# -----------------------------------------------------------------------------


def test_evolutionary_multimodal_prompt() -> None:
    """Test EvolutionaryOptimizer with multimodal prompt."""
    test_multimodal_prompt(EvolutionaryOptimizer)


def test_metaprompt_multimodal_prompt() -> None:
    """
    Test MetaPromptOptimizer with multimodal prompt.
    
    Expected to fail until MetaPromptOptimizer supports content_parts.
    """
    test_multimodal_prompt(MetaPromptOptimizer)


def test_fewshot_multimodal_prompt() -> None:
    """
    Test FewShotBayesianOptimizer with multimodal prompt.
    
    Expected to fail until FewShotBayesianOptimizer supports content_parts.
    """
    test_multimodal_prompt(FewShotBayesianOptimizer)


def test_gepa_multimodal_prompt() -> None:
    """
    Test GepaOptimizer with multimodal prompt.
    
    Expected to fail until GepaOptimizer supports content_parts.
    """
    test_multimodal_prompt(GepaOptimizer)


def test_hierarchical_multimodal_prompt() -> None:
    """Test HierarchicalReflectiveOptimizer with multimodal prompt."""
    test_multimodal_prompt(HierarchicalReflectiveOptimizer)


if __name__ == "__main__":
    # Run tests manually
    for optimizer_class in [
        EvolutionaryOptimizer,
        MetaPromptOptimizer,
        FewShotBayesianOptimizer,
        GepaOptimizer,
        HierarchicalReflectiveOptimizer,
    ]:
        try:
            test_multimodal_prompt(optimizer_class)
        except Exception as e:
            print(f"❌ {optimizer_class.__name__}: FAILED - {e}")



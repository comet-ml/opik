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

from __future__ import annotations

from typing import Any

import pytest
import os

import opik
from opik.evaluation.metrics import LevenshteinRatio
from opik.evaluation.metrics.score_result import ScoreResult

from opik_optimizer import (
    ChatPrompt,
    EvolutionaryOptimizer,
    MetaPromptOptimizer,
    FewShotBayesianOptimizer,
    GepaOptimizer,
    HierarchicalReflectiveOptimizer,
    ParameterOptimizer,
)
from ..utils import (
    assert_multimodal_structure_preserved,
    create_optimizer_config,
    get_parameter_space,
    run_optimizer,
    system_message,
    user_message,
)


pytestmark = [
    pytest.mark.integration,
    pytest.mark.skipif(
        not os.getenv("OPENAI_API_KEY"),
        reason="OPENAI_API_KEY environment variable required",
    ),
]


def hazard_metric(dataset_item: dict[str, Any], llm_output: str) -> ScoreResult:
    """Metric for driving hazard detection with reason for HierarchicalReflective."""
    metric = LevenshteinRatio()
    reference = dataset_item.get("hazard") or dataset_item.get("label")
    result = metric.score(reference=reference, output=llm_output)
    return ScoreResult(
        name=result.name,
        value=result.value,
        reason=f"Similarity to expected hazard: {result.value:.2f}",
    )


def create_multimodal_prompt() -> ChatPrompt:
    """Create a multimodal ChatPrompt with text and image content."""
    system_prompt = (
        "You are an image analysis assistant. Describe what you see "
        "in the image and answer any questions about it."
    )
    return ChatPrompt(
        name="multimodal-prompt",
        messages=[
            system_message(system_prompt),
            user_message(
                [
                    {"type": "text", "text": "{question}"},
                    {"type": "image_url", "image_url": {"url": "{image}"}},
                ]
            ),
        ],
        model="openai/gpt-5-nano",
    )


# -----------------------------------------------------------------------------
# Tests
# -----------------------------------------------------------------------------


@pytest.mark.parametrize(
    "optimizer_class",
    [
        EvolutionaryOptimizer,
        MetaPromptOptimizer,
        FewShotBayesianOptimizer,
        GepaOptimizer,
        HierarchicalReflectiveOptimizer,
        ParameterOptimizer,
    ],
)
def test_multimodal_prompt(
    optimizer_class: type,
    setup_driving_hazard_dataset: opik.Dataset,
) -> None:
    """
    Test that optimizers can handle multimodal prompts with text and images.

    This test verifies:
    1. Optimization completes with multimodal content
    2. Multimodal structure (content parts) is preserved
    3. Image URL placeholders are preserved
    4. Text content can be optimized

    """
    # Create multimodal prompt
    original_prompt = create_multimodal_prompt()

    # Get multimodal dataset (created once per session via conftest)
    dataset = setup_driving_hazard_dataset

    # Create optimizer with minimal config
    config = create_optimizer_config(optimizer_class, verbose=0)
    optimizer = optimizer_class(**config)
    results = run_optimizer(
        optimizer_class=optimizer_class,
        optimizer=optimizer,
        prompt=original_prompt,
        dataset=dataset,
        metric=hazard_metric,
        parameter_space=get_parameter_space(),
        n_samples=1,
        max_trials=1,
    )

    # Validate results structure
    assert results.optimizer == optimizer_class.__name__, (
        f"Expected {optimizer_class.__name__}, got {results.optimizer}"
    )

    # Get optimized prompt - handle both ChatPrompt and list returns
    optimized_prompt = results.prompt

    if isinstance(optimized_prompt, ChatPrompt):
        assert_multimodal_structure_preserved(original_prompt, optimized_prompt)
    elif isinstance(optimized_prompt, list):
        # Some algorithms may return a list[dict] for prompts.
        # Re-wrap to use shared assertions and keep behavior identical.
        wrapped = ChatPrompt(messages=optimized_prompt)
        assert_multimodal_structure_preserved(original_prompt, wrapped)
    else:
        pytest.fail(f"Unexpected prompt type: {type(optimized_prompt)}")

    print(f"✅ {optimizer_class.__name__}: Multimodal prompt - PASSED")

"""
E2E tests for multimodal prompt optimization with text and images.

Tests verify that all prompt-focused optimizers:
1. Complete optimization with multimodal content
2. Preserve multimodal structure (content parts)
3. Preserve image URL placeholders
4. Allow text content to be optimized

The optimizer model and evaluated agent model are configured independently.
Provider-boundary assertions verify native image evidence during optimization.
"""

from __future__ import annotations

from typing import Any
from copy import deepcopy
from opik_optimizer.core import llm_calls

import pytest
import os

import opik
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


def color_metric(dataset_item: dict[str, Any], llm_output: str) -> ScoreResult:
    """Score a synthetic image's color with a useful reason for reflection."""
    expected = dataset_item["label"]
    value = float(llm_output.strip().lower() == expected)
    return ScoreResult(
        name="color_match",
        value=value,
        reason=f"Expected the solid color {expected}; got {llm_output!r}",
    )


def create_multimodal_prompt() -> ChatPrompt:
    """Create a multimodal ChatPrompt with text and image content."""
    system_prompt = "Always reply with the word unknown."
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
        model="openai/gpt-4o-mini",
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
    setup_synthetic_image_dataset: opik.Dataset,
    monkeypatch: pytest.MonkeyPatch,
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

    # Two generated color squares; no benchmark, customer or repository images are sent.
    dataset = setup_synthetic_image_dataset

    # Create optimizer with minimal config
    config = create_optimizer_config(optimizer_class, verbose=0)
    if optimizer_class is not ParameterOptimizer:
        config.update(
            model="openai/gpt-4o",
            skip_perfect_score=False,
            max_reasoning_examples=1,
        )
    if optimizer_class is FewShotBayesianOptimizer:
        config.update(min_examples=1, max_examples=1)
    optimizer = optimizer_class(**config)
    requests = []
    original_invoke, original_async = (
        llm_calls._invoke_traced,
        llm_calls._invoke_traced_async,
    )

    def observe(*args: Any, **kwargs: Any) -> Any:
        requests.append(deepcopy(kwargs))
        return original_invoke(*args, **kwargs)

    async def observe_async(*args: Any, **kwargs: Any) -> Any:
        requests.append(deepcopy(kwargs))
        return await original_async(*args, **kwargs)

    monkeypatch.setattr(llm_calls, "_invoke_traced", observe)
    monkeypatch.setattr(llm_calls, "_invoke_traced_async", observe_async)
    results = run_optimizer(
        optimizer_class=optimizer_class,
        optimizer=optimizer,
        prompt=original_prompt,
        dataset=dataset,
        metric=color_metric,
        parameter_space=get_parameter_space(),
        n_samples=1,
        max_trials=4,
    )

    if optimizer_class is not ParameterOptimizer:
        assert requests, (
            "The run must exercise optimizer reasoning, not just baseline evaluation"
        )
        assert all(request["model"] == "openai/gpt-4o" for request in requests)
        images = [
            part
            for request in requests
            for message in request["messages"]
            if isinstance(message.get("content"), list)
            for part in message["content"]
            if part.get("type") == "image_url"
        ]
        assert images, "The optimizer must receive native image evidence"
    assert results.prompt.model == original_prompt.model

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

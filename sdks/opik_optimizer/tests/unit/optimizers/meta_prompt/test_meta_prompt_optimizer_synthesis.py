"""
Unit tests for synthesis prompt wiring in MetaPromptOptimizer.
"""

from __future__ import annotations

from collections.abc import Callable
from typing import Any
from unittest.mock import MagicMock

import pytest

from opik import Dataset
from opik_optimizer import ChatPrompt
from opik_optimizer.algorithms.meta_prompt_optimizer.meta_prompt_optimizer import (
    MetaPromptOptimizer,
)
from opik_optimizer.algorithms.meta_prompt_optimizer.ops import candidate_ops
from opik_optimizer.algorithms.meta_prompt_optimizer.ops.candidate_ops import (
    AgentBundleCandidate,
)


pytestmark = pytest.mark.usefixtures("disable_rate_limiting")


def _make_dataset() -> MagicMock:
    dataset = MagicMock(spec=Dataset)
    dataset.name = "test-dataset"
    dataset.id = "dataset-123"
    dataset.get_items.return_value = [{"id": "1", "question": "Q1", "answer": "A1"}]
    return dataset


def test_synthesis_prompts_called_on_schedule(
    mock_opik_client: Callable[..., MagicMock],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    mock_opik_client()
    dataset = _make_dataset()

    optimizer = MetaPromptOptimizer(model="gpt-4o")
    optimizer.prompts_per_round = 1
    optimizer.synthesis_prompts_per_round = 1
    optimizer.synthesis_start_round = 0
    optimizer.synthesis_round_interval = 1

    called = {"synthesis": 0}

    def fake_generate_agent_bundle_candidates(
        **kwargs: Any,
    ) -> list[AgentBundleCandidate]:
        prompt = ChatPrompt(system="baseline", user="{question}")
        return [AgentBundleCandidate(prompts={prompt.name: prompt}, metadata={})]

    def fake_generate_synthesis_prompts(**kwargs: Any) -> list[ChatPrompt]:
        called["synthesis"] += 1
        return [ChatPrompt(system="synth", user="{question}")]

    monkeypatch.setattr(
        candidate_ops,
        "generate_agent_bundle_candidates",
        fake_generate_agent_bundle_candidates,
    )
    monkeypatch.setattr(
        candidate_ops, "generate_synthesis_prompts", fake_generate_synthesis_prompts
    )
    monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **kwargs: 0.1)

    prompt = ChatPrompt(system="baseline", user="{question}")

    def metric(dataset_item: dict[str, Any], llm_output: str) -> float:
        return 1.0

    optimizer.optimize_prompt(
        prompt=prompt,
        dataset=dataset,
        metric=metric,
        max_trials=1,
    )

    assert called["synthesis"] == 1

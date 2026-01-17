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
from opik_optimizer import task_evaluator


pytestmark = pytest.mark.usefixtures(
    "disable_rate_limiting", "suppress_expected_optimizer_warnings"
)


from tests.unit.test_helpers import make_mock_dataset


def _make_dataset() -> MagicMock:
    return make_mock_dataset(
        [{"id": "1", "question": "Q1", "answer": "A1"}],
        name="test-dataset",
        dataset_id="dataset-123",
    )


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

    def fake_generate_candidate_prompts(**kwargs: Any) -> list[ChatPrompt]:
        """Mock for single prompt optimization path."""
        return [ChatPrompt(system="baseline", user="{question}")]

    def fake_generate_synthesis_prompts(**kwargs: Any) -> list[ChatPrompt]:
        called["synthesis"] += 1
        return [ChatPrompt(system="synth", user="{question}")]

    # For single prompt optimization, generate_candidate_prompts is called (not generate_agent_bundle_candidates)
    monkeypatch.setattr(
        candidate_ops,
        "generate_candidate_prompts",
        fake_generate_candidate_prompts,
    )
    monkeypatch.setattr(
        candidate_ops, "generate_synthesis_prompts", fake_generate_synthesis_prompts
    )

    # Mock task_evaluator.evaluate to return a fixed score
    monkeypatch.setattr(
        task_evaluator,
        "evaluate",
        lambda **kwargs: 0.1,
    )

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

from __future__ import annotations

import pytest

from opik_optimizer import ChatPrompt
from opik_optimizer.algorithms.evolutionary_optimizer.ops import tool_ops


def _build_prompt_with_tool() -> ChatPrompt:
    return ChatPrompt(
        system="You are helpful.",
        user="{input}",
        tools=[
            {
                "type": "function",
                "function": {
                    "name": "search",
                    "description": "Search docs",
                    "parameters": {
                        "type": "object",
                        "properties": {"query": {"type": "string"}},
                    },
                },
            }
        ],
    )


def test_apply_tool_description_update_degrades_on_generation_error(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    prompt = _build_prompt_with_tool()

    class DummyOptimizer:
        prompts_per_round = 3
        current_optimization_id = "opt-123"
        project_name = "test-project"

        def get_history_rounds(self) -> list[object]:
            return []

    optimizer = DummyOptimizer()

    def boom(**kwargs: object) -> list[ChatPrompt]:
        _ = kwargs
        raise RuntimeError("llm unavailable")

    monkeypatch.setattr(
        "opik_optimizer.algorithms.evolutionary_optimizer.ops.tool_ops.toolcalling_utils.generate_tool_description_candidates",
        boom,
    )

    result = tool_ops.apply_tool_description_update(
        optimizer=optimizer,
        prompt=prompt,
        tool_names=None,
        round_num=0,
        metric=lambda *_: 1.0,
    )

    assert result is prompt
    assert optimizer.prompts_per_round == 3


def test_apply_tool_description_update_cleans_temporary_prompts_per_round(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    prompt = _build_prompt_with_tool()

    class DummyOptimizer:
        current_optimization_id = "opt-123"
        project_name = "test-project"

        def get_history_rounds(self) -> list[object]:
            return []

    optimizer = DummyOptimizer()

    def boom(**kwargs: object) -> list[ChatPrompt]:
        _ = kwargs
        raise RuntimeError("llm unavailable")

    monkeypatch.setattr(
        "opik_optimizer.algorithms.evolutionary_optimizer.ops.tool_ops.toolcalling_utils.generate_tool_description_candidates",
        boom,
    )

    _ = tool_ops.apply_tool_description_update(
        optimizer=optimizer,
        prompt=prompt,
        tool_names=None,
        round_num=0,
        metric=lambda *_: 1.0,
    )

    assert not hasattr(optimizer, "prompts_per_round")

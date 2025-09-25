from __future__ import annotations

from typing import Any
from pathlib import Path

import pytest

pytest.importorskip("gepa", reason="gepa package required for GEPA adapter tests")


class DummyMetricResult:
    def __init__(self, value: float) -> None:
        self.value = value


class DummyOptimizer:
    def __init__(self) -> None:
        self._gepa_live_metric_calls = 0


def test_adapter_evaluate_uses_metric(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    monkeypatch.setenv("HOME", str(tmp_path))

    from opik_optimizer.gepa_optimizer.adapter import OpikDataInst, OpikGEPAAdapter
    from opik_optimizer.optimization_config.chat_prompt import ChatPrompt

    prompt = ChatPrompt(system="Answer", user="{input}")

    # Fake agent class returned by create_litellm_agent_class
    class DummyAgent:
        project_name = "dummy"

        def __init__(self, prompt: ChatPrompt) -> None:
            self.prompt = prompt

        def invoke(self, messages: Any) -> str:
            # Echo back the question as answer for deterministic scoring
            return "A"

    monkeypatch.setattr(
        "opik_optimizer.gepa_optimizer.adapter.create_litellm_agent_class",
        lambda _prompt: lambda prompt: DummyAgent(prompt),
    )

    def metric(dataset_item: dict[str, Any], llm_output: str) -> DummyMetricResult:
        expected = str(dataset_item.get("answer", ""))
        score = 1.0 if expected and expected in llm_output else 0.0
        return DummyMetricResult(score)

    inst = OpikDataInst(
        input_text="Which?",
        answer="A",
        additional_context={},
        opik_item={"input": "Which?", "answer": "A"},
    )

    adapter = OpikGEPAAdapter(
        base_prompt=prompt,
        optimizer=DummyOptimizer(),
        metric=metric,
        system_fallback="Answer",
    )

    batch = [inst]
    result = adapter.evaluate(batch, {"system_prompt": "Answer"}, capture_traces=True)

    assert result.scores == [1.0]
    assert result.outputs == [{"output": "A"}]
    assert result.trajectories is not None
    assert result.trajectories[0]["score"] == 1.0

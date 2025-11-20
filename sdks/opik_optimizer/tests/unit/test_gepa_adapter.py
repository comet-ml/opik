from __future__ import annotations

from typing import Any
from pathlib import Path

from opik_optimizer import ChatPrompt
import pytest

try:
    import gepa  # noqa: F401
except ImportError:
    pytest.fail("gepa package is required for GEPA adapter tests")  # pragma: no cover


class DummyMetricResult:
    def __init__(self, value: float) -> None:
        self.value = value


class DummyAgent:
    project_name = "dummy"

    def __init__(self, prompt: ChatPrompt) -> None:
        self.prompt = prompt
        self.optimizer: Any | None = None  # Set by DummyOptimizer._instantiate_agent

    def invoke(self, messages: Any) -> str:
        # Echo back the question as answer for deterministic scoring
        return "A"


class DummyOptimizer:
    def __init__(self) -> None:
        self._gepa_live_metric_calls = 0
        self.llm_call_counter = 0
        self.tool_call_counter = 0
        self.project_name = "dummy"
        self.name = "DummyOptimizer"

    def _increment_llm_counter(self) -> None:
        self.llm_call_counter += 1

    def _increment_tool_counter(self) -> None:
        self.tool_call_counter += 1

    def _reset_counters(self) -> None:
        self.llm_call_counter = 0
        self.tool_call_counter = 0

    def _instantiate_agent(
        self, prompt: ChatPrompt, agent_class: Any | None = None
    ) -> Any:
        cls = agent_class or (lambda p: DummyAgent(p))
        agent = cls(prompt)
        agent.optimizer = self  # Mimic BaseOptimizer behavior
        return agent


def test_adapter_evaluate_uses_metric(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    monkeypatch.setenv("HOME", str(tmp_path))

    from opik_optimizer.algorithms.gepa_optimizer.adapter import (
        OpikDataInst,
        OpikGEPAAdapter,
    )
    from opik_optimizer import ChatPrompt

    prompt = ChatPrompt(system="Answer", user="{input}")

    monkeypatch.setattr(
        "opik_optimizer.algorithms.gepa_optimizer.adapter.create_litellm_agent_class",
        lambda _prompt, optimizer_ref=None: lambda prompt: DummyAgent(prompt),
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

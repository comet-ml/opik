from __future__ import annotations

from typing import Any
from pathlib import Path
from types import SimpleNamespace

import pytest

from opik_optimizer import ChatPrompt

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
        self.optimizer: Any | None = None
        self.invoke_calls: list[list[dict[str, Any]]] = []

    def invoke(self, messages: Any) -> str:
        self.invoke_calls.append(messages)
        return "A"


class DummyOptimizer:
    def __init__(self) -> None:
        self._gepa_live_metric_calls = 0
        self.llm_call_counter = 0
        self.tool_call_counter = 0
        self.project_name = "dummy"
        self.name = "DummyOptimizer"
        self.n_threads = 1
        self.current_optimization_id = "opt-123"
        self.agent_instances: list[Any] = []

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
        self.agent_instances.append(agent)
        return agent

    def _prepare_experiment_config(
        self, **_: Any
    ) -> dict[str, Any]:  # pragma: no cover - simple helper
        return {"project_name": "TestProject"}


def test_adapter_evaluate_uses_metric(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    monkeypatch.setenv("HOME", str(tmp_path))

    from opik_optimizer.algorithms.gepa_optimizer.adapter import (
        OpikDataInst,
        OpikGEPAAdapter,
    )

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
        opik_item={"id": "item-1", "input": "Which?", "answer": "A"},
    )

    optimizer = DummyOptimizer()
    batch = [inst]

    def fake_evaluate(*_args: Any, **kwargs: Any) -> tuple[float, Any]:
        evaluated_task = kwargs["evaluated_task"]
        assert kwargs["dataset_item_ids"] == ["item-1"]
        test_results = []
        for inst_item in batch:
            output = evaluated_task(inst_item.opik_item)
            test_case = SimpleNamespace(
                dataset_item_id=inst_item.opik_item["id"],
                task_output=output,
            )
            score_result = SimpleNamespace(name=metric.__name__, value=1.0)
            test_results.append(
                SimpleNamespace(test_case=test_case, score_results=[score_result])
            )
        return 1.0, SimpleNamespace(test_results=test_results)

    monkeypatch.setattr(
        "opik_optimizer.algorithms.gepa_optimizer.adapter.task_evaluator.evaluate_with_result",
        fake_evaluate,
    )

    adapter = OpikGEPAAdapter(
        base_prompt=prompt,
        optimizer=optimizer,
        metric=metric,
        system_fallback="Answer",
        dataset=object(),
        experiment_config={"project_name": "TestProject"},
    )

    result = adapter.evaluate(batch, {"system_prompt": "Answer"}, capture_traces=True)

    assert result.scores == [1.0]
    assert result.outputs == [{"output": "A"}]
    assert result.trajectories is not None
    assert result.trajectories[0]["score"] == 1.0
    assert optimizer._gepa_live_metric_calls == 1
    assert optimizer.agent_instances, "Agent should be instantiated"
    assert optimizer.agent_instances[0].invoke_calls


def test_adapter_falls_back_without_ids(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    monkeypatch.setenv("HOME", str(tmp_path))

    from opik_optimizer.algorithms.gepa_optimizer.adapter import (
        OpikDataInst,
        OpikGEPAAdapter,
    )

    prompt = ChatPrompt(system="Answer", user="{input}")

    monkeypatch.setattr(
        "opik_optimizer.algorithms.gepa_optimizer.adapter.create_litellm_agent_class",
        lambda _prompt, optimizer_ref=None: lambda prompt: DummyAgent(prompt),
    )

    def metric(dataset_item: dict[str, Any], llm_output: str) -> DummyMetricResult:
        expected = str(dataset_item.get("answer", ""))
        return DummyMetricResult(0.3 if expected and expected in llm_output else 0)

    inst = OpikDataInst(
        input_text="Which?",
        answer="A",
        additional_context={},
        opik_item={"input": "Which?", "answer": "A"},
    )

    optimizer = DummyOptimizer()
    evaluate_called = False

    def fake_evaluate(*_args: Any, **_kwargs: Any) -> tuple[float, Any]:
        nonlocal evaluate_called
        evaluate_called = True
        return 0.0, SimpleNamespace(test_results=[])

    monkeypatch.setattr(
        "opik_optimizer.algorithms.gepa_optimizer.adapter.task_evaluator.evaluate_with_result",
        fake_evaluate,
    )

    adapter = OpikGEPAAdapter(
        base_prompt=prompt,
        optimizer=optimizer,
        metric=metric,
        system_fallback="Answer",
        dataset=object(),
        experiment_config=None,
    )

    result = adapter.evaluate([inst], {"system_prompt": "Answer"}, capture_traces=False)

    assert not evaluate_called, "Should not call task evaluator without dataset IDs"
    assert result.scores == [0.3]
    assert result.outputs == [{"output": "A"}]
    assert result.trajectories is None
    assert optimizer._gepa_live_metric_calls == 1

import sys
import types
from typing import Any

if "gepa" not in sys.modules:
    dummy_gepa = types.ModuleType("gepa")
    dummy_gepa.optimize = lambda **kwargs: None
    sys.modules["gepa"] = dummy_gepa

import pytest

from opik_optimizer.gepa_optimizer import GepaOptimizer
from opik_optimizer.optimization_config.chat_prompt import ChatPrompt
import opik_optimizer.gepa_optimizer.gepa_optimizer as gepa_module


class DummyScore:
    def __init__(self, value: float) -> None:
        self.value = value


class DummyDataset:
    name = "dummy"
    id = "ds-123"

    def get_items(self, count: int | None = None, *args: Any, **kwargs: Any) -> list[dict[str, Any]]:
        data = [
            {"id": "1", "question": "Q1", "answer": "A1"},
            {"id": "2", "question": "Q2", "answer": "A2"},
        ]
        if count is not None and isinstance(count, int):
            return data[:count]
        return data


class DummyOptimization:
    def __init__(self) -> None:
        self.id = "opt-123"
        self.status_updates: list[str] = []

    def update(self, status: str) -> None:
        self.status_updates.append(status)


class DummyOpikClient:
    def __init__(self, project_name: str | None = None) -> None:
        self.project_name = project_name

    def create_optimization(
        self,
        dataset_name: str,
        objective_name: str,
        name: str | None = None,
        metadata: dict[str, Any] | None = None,
    ) -> DummyOptimization:
        return DummyOptimization()

    def get_dataset(self, dataset_name: str) -> DummyDataset:
        return DummyDataset()


class DummyGepaResult:
    def __init__(self) -> None:
        self.candidates = [{"system_prompt": "Improved prompt"}]
        self.val_aggregate_scores = [0.85]
        self.best_idx = 0
        self.num_candidates = 1
        self.total_metric_calls = 1
        self.parents = []


class DummyAgent:
    project_name = "dummy-project"

    def __init__(self, prompt: ChatPrompt) -> None:
        self.prompt = prompt

    def invoke(self, messages: list[dict[str, str]]) -> str:
        return "dummy-response"


@pytest.fixture(autouse=True)
def _patch_gepa_optimize(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setitem(
        sys.modules,
        "gepa",
        types.SimpleNamespace(optimize=lambda **kwargs: DummyGepaResult()),
    )


def test_optimize_prompt_disables_experiment_reporting(monkeypatch: pytest.MonkeyPatch) -> None:
    call_order: list[str] = []

    def fake_disable() -> None:
        call_order.append("disable")

    def fake_enable() -> None:
        call_order.append("enable")

    monkeypatch.setattr(
        gepa_module,
        "disable_experiment_reporting",
        fake_disable,
    )
    monkeypatch.setattr(
        gepa_module,
        "enable_experiment_reporting",
        fake_enable,
    )

    monkeypatch.setattr(gepa_module.opik, "Opik", DummyOpikClient)

    monkeypatch.setattr(
        gepa_module,
        "OpikGEPAAdapter",
        lambda **kwargs: object(),
    )

    def fake_agent_factory(prompt: ChatPrompt, optimizer: Any | None = None) -> type[DummyAgent]:
        return DummyAgent

    monkeypatch.setattr(
        gepa_module,
        "create_litellm_agent_class",
        fake_agent_factory,
    )

    monkeypatch.setattr(
        gepa_module.task_evaluator,
        "evaluate",
        lambda *args, **kwargs: 0.75,
    )

    monkeypatch.setattr(
        GepaOptimizer,
        "_evaluate_prompt_logged",
        lambda self, *args, **kwargs: 0.8,
    )
    monkeypatch.setattr(
        GepaOptimizer,
        "validate_optimization_inputs",
        lambda self, prompt, dataset, metric: None,
    )

    dataset = DummyDataset()
    prompt = ChatPrompt(system="You are helpful", user="{question}")

    def metric(dataset_item: dict[str, Any], llm_output: str) -> DummyScore:
        return DummyScore(0.5)

    optimizer = GepaOptimizer(model="openai/gpt-4o-mini", reflection_model="openai/gpt-4o")
    result = optimizer.optimize_prompt(
        prompt=prompt,
        dataset=dataset,
        metric=metric,
        n_samples=1,
    )

    assert call_order == ["disable", "enable"]
    assert result.optimization_id == "opt-123"
    assert result.dataset_id == dataset.id

from collections.abc import Callable
from typing import Any

import pytest

from opik_optimizer import ChatPrompt, FewShotBayesianOptimizer
from opik_optimizer.algorithms.few_shot_bayesian_optimizer import (
    few_shot_bayesian_optimizer,
)


class _StubAgent:
    def invoke(self, messages: list[dict[str, str]], seed: int | None = None) -> str:
        return "stubbed-response"


class _DummyDataset:
    name = "dummy-dataset"
    id = "dataset-id"

    def __init__(self, items: list[dict[str, Any]]) -> None:
        self._items = items

    def get_items(self, nb_samples: int | None = None) -> list[dict[str, Any]]:
        return self._items[:nb_samples] if nb_samples else list(self._items)


@pytest.fixture()
def dummy_prompt() -> ChatPrompt:
    return ChatPrompt(system="system", user="{question}")


def _dummy_metric(dataset_item: dict[str, Any], llm_output: str) -> float:
    return 0.0


def test_evaluate_prompt_sampling_is_seeded(
    monkeypatch: pytest.MonkeyPatch, dummy_prompt: ChatPrompt
) -> None:
    items = [{"id": f"id-{i}", "question": f"q-{i}"} for i in range(10)]
    dataset = _DummyDataset(items)
    optimizer = FewShotBayesianOptimizer(model="openai/gpt-4o-mini", seed=123)

    # Avoid real LLM calls
    monkeypatch.setattr(optimizer, "_instantiate_agent", lambda *_, **__: _StubAgent())

    captured_ids: list[list[str]] = []

    def fake_evaluate(
        *,
        dataset_item_ids: list[str] | None,
        metric: Callable,
        **kwargs: Any,
    ) -> float:
        captured_ids.append(dataset_item_ids or [])
        return 0.0

    monkeypatch.setattr(
        few_shot_bayesian_optimizer.task_evaluator, "evaluate", fake_evaluate
    )

    optimizer._evaluate_prompt(
        prompt=dummy_prompt,
        dataset=dataset,
        metric=_dummy_metric,
        n_samples=3,
    )

    # Change global random state; the sample should stay the same
    import random

    random.seed(999)
    optimizer._evaluate_prompt(
        prompt=dummy_prompt,
        dataset=dataset,
        metric=_dummy_metric,
        n_samples=3,
    )

    assert len(captured_ids) == 2
    assert captured_ids[0] == captured_ids[1]

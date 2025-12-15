import json
from typing import Any

from deap import base
import pytest

from opik_optimizer.algorithms.evolutionary_optimizer.ops import crossover_ops


def _ensure_creator_initialized() -> None:
    if not hasattr(crossover_ops.creator, "FitnessMax"):
        crossover_ops.creator.create("FitnessMax", base.Fitness, weights=(1.0,))
    if not hasattr(crossover_ops.creator, "Individual"):
        crossover_ops.creator.create(
            "Individual", list, fitness=crossover_ops.creator.FitnessMax
        )


def _make_individual(messages: list[dict[str, Any]]) -> Any:
    _ensure_creator_initialized()
    individual = crossover_ops.creator.Individual(messages)
    num_weights = len(getattr(individual.fitness, "weights", (0.0,)))
    individual.fitness.values = (0.0,) * max(1, num_weights)
    return individual


def test_llm_crossover_falls_back_when_child_is_invalid(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    parent1 = _make_individual([{"role": "user", "content": "{question}"}])
    parent2 = _make_individual([{"role": "assistant", "content": "Sure"}])

    def fake_call_model(**_: Any) -> str:
        # Return a child prompt whose only message has empty content once trimmed
        return json.dumps([[{"role": "user", "content": "   "}]])

    fallback_called: dict[str, Any] = {}
    fallback_children = ("child1", "child2")

    def fake_deap_crossover(ind1: Any, ind2: Any, verbose: int = 1) -> tuple[Any, Any]:
        fallback_called["called"] = True
        assert ind1 is parent1
        assert ind2 is parent2
        return fallback_children

    monkeypatch.setattr(crossover_ops._llm_calls, "call_model", fake_call_model)
    monkeypatch.setattr(crossover_ops, "deap_crossover", fake_deap_crossover)

    child1, child2 = crossover_ops.llm_deap_crossover(
        parent1,
        parent2,
        output_style_guidance="",
        model="openai/responses/gpt",
        model_parameters={},
        verbose=0,
    )

    assert fallback_called.get("called") is True
    assert (child1, child2) == fallback_children

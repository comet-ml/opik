"""Invariants every Evolutionary mutation and crossover must keep.

Mutation and crossover pick among several strategies at random (LLM rewrites,
sentence shuffles, word swaps, chunk crossover). Rather than pinning which
branch runs, these tests sweep many seeds and check what must always hold: the
prompt keeps its shape, only allowed roles change, template variables survive,
and an LLM failure degrades to a valid prompt instead of raising.
"""

from __future__ import annotations

import random
from typing import Any

import pytest
from deap import creator

from opik_optimizer import ChatPrompt
from opik_optimizer.algorithms.evolutionary_optimizer.ops import (
    crossover_ops,
    mutation_ops,
)
from opik_optimizer.core import llm_calls
from opik_optimizer.utils.prompt_library import PromptLibrary
from tests.unit.characterization import harness

SEEDS = range(40)
SYSTEM = "You are a helpful assistant. Answer briefly. Use the context. Be exact."


def _individual(system: str = SYSTEM) -> Any:
    return creator.Individual(
        {
            "chat-prompt": [
                {"role": "system", "content": system},
                {"role": "user", "content": "Question: {question}"},
            ]
        }
    )


def _assert_valid_child(child: Any, parent: Any) -> None:
    assert set(child) == set(parent)
    for name, messages in child.items():
        original = parent[name]
        assert [m["role"] for m in messages] == [m["role"] for m in original]
        assert messages[1] == original[1]  # user role is not optimizable here
        assert isinstance(messages[0]["content"], str) and messages[0]["content"]


@pytest.fixture
def evolutionary(install_fakes: Any) -> Any:
    scenario = harness.build_scenario("S1_single")
    install_fakes(scenario)
    # Constructing the optimizer registers DEAP's Individual type.
    return harness.make_optimizer("EvolutionaryOptimizer")


@pytest.fixture(params=["llm_answers", "llm_fails"])
def llm_mode(request: Any, monkeypatch: pytest.MonkeyPatch, evolutionary: Any) -> str:
    if request.param == "llm_fails":

        def unavailable(**_kwargs: Any) -> Any:
            raise RuntimeError("provider unavailable")

        monkeypatch.setattr(llm_calls, "call_model", unavailable)
    return request.param


def test_mutation_keeps_shape_roles_and_variables(
    evolutionary: Any, llm_mode: str
) -> None:
    prompts = PromptLibrary(evolutionary.DEFAULT_PROMPTS)
    parent = _individual()
    changed = 0
    for seed in SEEDS:
        child = mutation_ops.deap_mutation(
            individual=creator.Individual({k: list(v) for k, v in parent.items()}),
            optimizer=None,
            current_population=[parent, _individual(SYSTEM + " Think.")],
            output_style_guidance="Be concise.",
            initial_prompts={"chat-prompt": ChatPrompt(messages=parent["chat-prompt"])},
            model=harness.MODEL,
            model_parameters={},
            diversity_threshold=0.5,
            optimization_id=None,
            verbose=0,
            prompts=prompts,
            allowed_roles={"system"},
            rng=random.Random(seed),
        )
        _assert_valid_child(child, parent)
        changed += child["chat-prompt"][0]["content"] != SYSTEM
    # Non-LLM strategies still vary the prompt when the provider is down.
    assert changed > 0


def test_crossover_keeps_shape_roles_and_variables(
    evolutionary: Any, llm_mode: str
) -> None:
    prompts = PromptLibrary(evolutionary.DEFAULT_PROMPTS)
    first = _individual()
    second = _individual("Reply in one word. Check the facts twice. Stay on task.")
    for seed in SEEDS:
        for use_llm in (False, True):
            if use_llm:
                children = crossover_ops.llm_deap_crossover(
                    creator.Individual(dict(first)),
                    creator.Individual(dict(second)),
                    output_style_guidance="Be concise.",
                    model=harness.MODEL,
                    model_parameters={},
                    prompts=prompts,
                    use_semantic=seed % 2 == 0,
                    verbose=0,
                    rng=random.Random(seed),
                )
            else:
                children = crossover_ops.deap_crossover(
                    creator.Individual(dict(first)),
                    creator.Individual(dict(second)),
                    verbose=0,
                    rng=random.Random(seed),
                )
            for child in children:
                _assert_valid_child(child, first)

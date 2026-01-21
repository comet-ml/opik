"""Unit tests for deap_mutation wiring/behavior in mutation_ops."""

from __future__ import annotations

import json
from typing import Any

import pytest

from opik_optimizer import ChatPrompt
from opik_optimizer.algorithms.evolutionary_optimizer.ops import mutation_ops
from tests.unit.fixtures import system_message, user_message
from tests.unit.test_helpers import make_fake_llm_call

pytestmark = pytest.mark.usefixtures("suppress_expected_optimizer_warnings")


class TestDeapMutation:
    """Tests for deap_mutation function."""

    def test_mutates_single_prompt(
        self, monkeypatch: pytest.MonkeyPatch, evo_prompts: Any
    ) -> None:
        from deap import creator

        if not hasattr(creator, "Individual"):
            creator.create("Individual", dict, fitness=None)

        call_count = {"n": 0}

        def controlled_random() -> float:
            call_count["n"] += 1
            if call_count["n"] <= 2:
                return 0.5
            return 0.3

        monkeypatch.setattr(
            "opik_optimizer.algorithms.evolutionary_optimizer.ops.mutation_ops.random.random",
            controlled_random,
        )
        monkeypatch.setattr(
            "opik_optimizer.algorithms.evolutionary_optimizer.ops.mutation_ops.random.choice",
            lambda seq: seq[0] if seq else "rephrase",
        )

        monkeypatch.setattr(
            "opik_optimizer.core.llm_calls.call_model",
            make_fake_llm_call(
                json.dumps([system_message("Mutated"), user_message("Question")])
            ),
        )

        monkeypatch.setattr(
            "opik_optimizer.algorithms.evolutionary_optimizer.ops.mutation_ops.reporting.display_success",
            lambda *_a, **_k: None,
        )
        monkeypatch.setattr(
            "opik_optimizer.algorithms.evolutionary_optimizer.ops.mutation_ops.helpers.calculate_population_diversity",
            lambda *_a, **_k: 0.5,
        )

        individual = creator.Individual(
            {
                "main": [
                    system_message("Original"),
                    user_message("Q"),
                ]
            }
        )
        initial_prompts = {"main": ChatPrompt(system="Initial", user="Q")}

        result = mutation_ops.deap_mutation(
            individual=individual,
            current_population=[individual],
            output_style_guidance="Be concise",
            initial_prompts=initial_prompts,
            model="gpt-4",
            model_parameters={},
            diversity_threshold=0.3,
            optimization_id="opt-123",
            verbose=0,
            prompts=evo_prompts,
        )

        assert hasattr(result, "keys")
        assert "main" in result

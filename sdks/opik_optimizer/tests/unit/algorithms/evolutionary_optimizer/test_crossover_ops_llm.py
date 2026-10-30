import random
from typing import Any

import pytest

from tests.unit.algorithms.evolutionary_optimizer._crossover_test_helpers import (
    make_deap_individual,
)
from tests.unit.fixtures import system_message

pytestmark = pytest.mark.usefixtures("suppress_expected_optimizer_warnings")


class TestLLMDeapCrossover:
    """Tests for llm_deap_crossover function."""

    def test_llm_crossover_fallback_on_error(
        self, monkeypatch: pytest.MonkeyPatch, evo_prompts: Any
    ) -> None:
        from opik_optimizer.algorithms.evolutionary_optimizer.ops.crossover_ops import (
            llm_deap_crossover,
        )

        def fake_call_model(**_kwargs: Any) -> None:
            raise Exception("LLM API error")

        monkeypatch.setattr("opik_optimizer.core.llm_calls.call_model", fake_call_model)
        monkeypatch.setattr(
            "opik_optimizer.algorithms.evolutionary_optimizer.ops.crossover_ops.display_message",
            lambda *_a, **_k: None,
        )

        random.seed(42)

        ind1 = make_deap_individual({"main": [system_message("First. Second.")]})
        ind2 = make_deap_individual({"main": [system_message("Alpha. Beta.")]})

        child1, child2 = llm_deap_crossover(
            ind1,
            ind2,
            output_style_guidance="Be concise",
            model="gpt-4",
            model_parameters={},
            prompts=evo_prompts,
            verbose=0,
        )

        assert "main" in child1
        assert "main" in child2

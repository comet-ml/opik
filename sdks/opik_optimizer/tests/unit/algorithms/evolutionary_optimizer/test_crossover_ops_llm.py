import random
from typing import Any

import pytest

from tests.unit.algorithms.evolutionary_optimizer._crossover_test_helpers import (
    make_deap_individual,
)
from tests.unit.fixtures import system_message, user_message

pytestmark = pytest.mark.usefixtures("suppress_expected_optimizer_warnings")


class TestLLMCrossoverMessages:
    """Tests for _llm_crossover_messages function."""

    def test_llm_crossover_success(
        self, monkeypatch: pytest.MonkeyPatch, evo_prompts: Any
    ) -> None:
        from opik_optimizer.algorithms.evolutionary_optimizer.ops.crossover_ops import (
            _llm_crossover_messages,
            CrossoverResponse,
        )

        mock_response = CrossoverResponse(
            child_1=[
                system_message("Blended system prompt"),
                user_message("Blended user prompt"),
            ],
            child_2=[
                system_message("Alternative system prompt"),
                user_message("Alternative user prompt"),
            ],
        )

        monkeypatch.setattr(
            "opik_optimizer.core.llm_calls.call_model",
            lambda **_kwargs: mock_response,
        )

        messages1 = [
            system_message("System A"),
            user_message("User A"),
        ]
        messages2 = [
            system_message("System B"),
            user_message("User B"),
        ]

        child1, child2 = _llm_crossover_messages(
            messages1,
            messages2,
            output_style_guidance="Be concise",
            model="gpt-4",
            model_parameters={},
            prompts=evo_prompts,
        )

        assert len(child1) == 2
        assert len(child2) == 2
        assert child1[0]["content"] == "Blended system prompt"


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
            "opik_optimizer.algorithms.evolutionary_optimizer.ops.crossover_ops.reporting.display_message",
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

    def test_llm_crossover_success(
        self, monkeypatch: pytest.MonkeyPatch, evo_prompts: Any
    ) -> None:
        from opik_optimizer.algorithms.evolutionary_optimizer.ops.crossover_ops import (
            llm_deap_crossover,
            CrossoverResponse,
        )

        mock_response = CrossoverResponse(
            child_1=[system_message("LLM generated 1")],
            child_2=[system_message("LLM generated 2")],
        )

        monkeypatch.setattr(
            "opik_optimizer.core.llm_calls.call_model", lambda **_kw: mock_response
        )
        monkeypatch.setattr(
            "opik_optimizer.algorithms.evolutionary_optimizer.ops.crossover_ops.reporting.display_message",
            lambda *_a, **_k: None,
        )

        ind1 = make_deap_individual({"main": [system_message("Original 1")]})
        ind2 = make_deap_individual({"main": [system_message("Original 2")]})

        child1, child2 = llm_deap_crossover(
            ind1,
            ind2,
            output_style_guidance="Be concise",
            model="gpt-4",
            model_parameters={},
            prompts=evo_prompts,
            verbose=0,
        )

        assert child1["main"][0]["content"] == "LLM generated 1"
        assert child2["main"][0]["content"] == "LLM generated 2"

    def test_llm_crossover_prefers_semantic_when_enabled(
        self, monkeypatch: pytest.MonkeyPatch, evo_prompts: Any
    ) -> None:
        from opik_optimizer.algorithms.evolutionary_optimizer.ops.crossover_ops import (
            llm_deap_crossover,
        )

        def fake_semantic(
            *_args: Any, **_kwargs: Any
        ) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
            return (
                [system_message("Semantic child 1")],
                [system_message("Semantic child 2")],
            )

        def fake_llm(
            *_args: Any, **_kwargs: Any
        ) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
            raise AssertionError(
                "LLM crossover should not be used when semantic succeeds"
            )

        monkeypatch.setattr(
            "opik_optimizer.algorithms.evolutionary_optimizer.ops.crossover_ops._semantic_crossover_messages",
            fake_semantic,
        )
        monkeypatch.setattr(
            "opik_optimizer.algorithms.evolutionary_optimizer.ops.crossover_ops._llm_crossover_messages",
            fake_llm,
        )

        ind1 = make_deap_individual({"main": [system_message("Original 1")]})
        ind2 = make_deap_individual({"main": [system_message("Original 2")]})

        child1, child2 = llm_deap_crossover(
            ind1,
            ind2,
            output_style_guidance="Be concise",
            model="gpt-4",
            model_parameters={},
            prompts=evo_prompts,
            use_semantic=True,
            verbose=0,
        )

        assert child1["main"][0]["content"] == "Semantic child 1"
        assert child2["main"][0]["content"] == "Semantic child 2"

    def test_llm_crossover_falls_back_from_semantic(
        self, monkeypatch: pytest.MonkeyPatch, evo_prompts: Any
    ) -> None:
        from opik_optimizer.algorithms.evolutionary_optimizer.ops.crossover_ops import (
            llm_deap_crossover,
            CrossoverResponse,
        )

        def fake_semantic(*_args: Any, **_kwargs: Any) -> None:
            raise Exception("Semantic failed")

        mock_response = CrossoverResponse(
            child_1=[system_message("LLM fallback 1")],
            child_2=[system_message("LLM fallback 2")],
        )

        monkeypatch.setattr(
            "opik_optimizer.algorithms.evolutionary_optimizer.ops.crossover_ops._semantic_crossover_messages",
            fake_semantic,
        )
        monkeypatch.setattr(
            "opik_optimizer.core.llm_calls.call_model", lambda **_kw: mock_response
        )
        monkeypatch.setattr(
            "opik_optimizer.algorithms.evolutionary_optimizer.ops.crossover_ops.reporting.display_message",
            lambda *_a, **_k: None,
        )

        ind1 = make_deap_individual({"main": [system_message("Original 1")]})
        ind2 = make_deap_individual({"main": [system_message("Original 2")]})

        child1, child2 = llm_deap_crossover(
            ind1,
            ind2,
            output_style_guidance="Be concise",
            model="gpt-4",
            model_parameters={},
            prompts=evo_prompts,
            use_semantic=True,
            verbose=0,
        )

        assert child1["main"][0]["content"] == "LLM fallback 1"
        assert child2["main"][0]["content"] == "LLM fallback 2"

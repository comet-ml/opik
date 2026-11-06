"""Unit tests for evolutionary semantic mutation strategies."""

from __future__ import annotations

from typing import Any

import pytest

from opik_optimizer import ChatPrompt
from opik_optimizer.algorithms.evolutionary_optimizer.ops import mutation_ops
from tests.unit.algorithms.evolutionary_optimizer._mutation_test_helpers import (
    force_random,
)
from tests.unit.test_helpers import make_fake_llm_call

pytestmark = pytest.mark.usefixtures("suppress_expected_optimizer_warnings")


class TestRadicalInnovationMutation:
    """Tests for _radical_innovation_mutation function."""

    def test_returns_original_on_llm_error(
        self, monkeypatch: pytest.MonkeyPatch, evo_prompts: Any
    ) -> None:
        monkeypatch.setattr(
            "opik_optimizer.core.llm_calls.call_model",
            make_fake_llm_call(raises=Exception("LLM API error")),
        )

        prompt = ChatPrompt(system="Original", user="Question")
        initial_prompt = ChatPrompt(system="Initial", user="Task")

        result = mutation_ops._radical_innovation_mutation(
            prompt=prompt,
            initial_prompt=initial_prompt,
            model="gpt-4",
            model_parameters={},
            output_style_guidance="Be concise",
            prompts=evo_prompts,
        )

        assert result is not prompt
        assert result.get_messages() == prompt.get_messages()


class TestSemanticMutation:
    """Tests for _semantic_mutation function."""

    def test_returns_original_on_error(
        self, monkeypatch: pytest.MonkeyPatch, evo_prompts: Any
    ) -> None:
        rng = force_random(monkeypatch, random_value=0.5, choice_value="rephrase")

        monkeypatch.setattr(
            "opik_optimizer.core.llm_calls.call_model",
            make_fake_llm_call(raises=Exception("LLM error")),
        )

        captured: dict[str, Any] = {}

        def fake_display_error(message: str, verbose: int = 1) -> None:
            _ = message
            captured["called"] = True

        monkeypatch.setattr(
            "opik_optimizer.algorithms.evolutionary_optimizer.ops.mutation_ops.display_error",
            fake_display_error,
        )

        prompt = ChatPrompt(system="Original", user="Question")

        result = mutation_ops._semantic_mutation(
            prompt=prompt,
            initial_prompt=prompt,
            model="gpt-4",
            model_parameters={},
            verbose=1,
            output_style_guidance="Be concise",
            prompts=evo_prompts,
            rng=rng,
        )

        assert result is not prompt
        assert result.get_messages() == prompt.get_messages()
        assert captured.get("called") is True

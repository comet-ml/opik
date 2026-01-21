"""Unit tests for evolutionary structural mutation strategies."""

from __future__ import annotations

from typing import Any

import pytest

from opik_optimizer import ChatPrompt
from opik_optimizer.algorithms.evolutionary_optimizer.ops import mutation_ops
from tests.unit.algorithms.evolutionary_optimizer._mutation_test_helpers import (
    force_random,
    patch_get_synonym,
)

pytestmark = pytest.mark.usefixtures("suppress_expected_optimizer_warnings")


class TestStructuralMutation:
    """Tests for _structural_mutation function."""

    def test_shuffle_sentences_path(
        self, monkeypatch: pytest.MonkeyPatch, evo_prompts: Any
    ) -> None:
        force_random(monkeypatch, random_value=0.1)

        prompt = ChatPrompt(
            system="First sentence. Second sentence. Third sentence.",
            user="Question here.",
        )

        result = mutation_ops._structural_mutation(
            prompt=prompt, model="gpt-4", model_parameters={}, prompts=evo_prompts
        )

        assert isinstance(result, ChatPrompt)

    def test_combine_sentences_path(
        self, monkeypatch: pytest.MonkeyPatch, evo_prompts: Any
    ) -> None:
        call_count = {"n": 0}

        def controlled_random() -> float:
            call_count["n"] += 1
            return 0.4

        monkeypatch.setattr(
            "opik_optimizer.algorithms.evolutionary_optimizer.ops.mutation_ops.random.random",
            controlled_random,
        )
        monkeypatch.setattr(
            "opik_optimizer.algorithms.evolutionary_optimizer.ops.mutation_ops.random.randint",
            lambda a, b: 0,
        )

        prompt = ChatPrompt(
            system="First sentence. Second sentence. Third sentence.",
            user="Question here.",
        )

        result = mutation_ops._structural_mutation(
            prompt=prompt, model="gpt-4", model_parameters={}, prompts=evo_prompts
        )

        assert isinstance(result, ChatPrompt)

    def test_split_sentences_path(
        self, monkeypatch: pytest.MonkeyPatch, evo_prompts: Any
    ) -> None:
        force_random(monkeypatch, random_value=0.8)
        monkeypatch.setattr(
            "opik_optimizer.algorithms.evolutionary_optimizer.ops.mutation_ops.random.randint",
            lambda a, b: a + (b - a) // 2 if b > a else a,
        )

        prompt = ChatPrompt(
            system="This is a longer sentence with many words here.",
            user="Question here.",
        )

        result = mutation_ops._structural_mutation(
            prompt=prompt, model="gpt-4", model_parameters={}, prompts=evo_prompts
        )

        assert isinstance(result, ChatPrompt)

    def test_fallback_to_word_mutation_for_single_sentence(
        self, monkeypatch: pytest.MonkeyPatch, evo_prompts: Any
    ) -> None:
        force_random(monkeypatch, random_value=0.1, randint_value=0)
        patch_get_synonym(monkeypatch, return_value="modified")

        prompt = ChatPrompt(system="Single sentence here", user="Question")

        result = mutation_ops._structural_mutation(
            prompt=prompt, model="gpt-4", model_parameters={}, prompts=evo_prompts
        )

        assert isinstance(result, ChatPrompt)

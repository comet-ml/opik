"""Unit tests for evolutionary semantic mutation strategies."""

from __future__ import annotations

import json
from typing import Any

import pytest

import opik_optimizer
from opik_optimizer import ChatPrompt
from opik_optimizer.algorithms.evolutionary_optimizer.ops import mutation_ops
from tests.unit.fixtures import system_message, user_message
from tests.unit.algorithms.evolutionary_optimizer._mutation_test_helpers import (
    force_random,
)
from tests.unit.test_helpers import make_fake_llm_call

pytestmark = pytest.mark.usefixtures("suppress_expected_optimizer_warnings")


class TestRadicalInnovationMutation:
    """Tests for _radical_innovation_mutation function."""

    def test_returns_new_prompt_on_success(
        self, monkeypatch: pytest.MonkeyPatch, evo_prompts: Any
    ) -> None:
        monkeypatch.setattr(
            "opik_optimizer.core.llm_calls.call_model",
            make_fake_llm_call(
                json.dumps(
                    [
                        system_message("New innovative prompt"),
                        user_message("{input}"),
                    ]
                )
            ),
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

        assert isinstance(result, ChatPrompt)
        messages = result.get_messages()
        assert any("innovative" in str(m.get("content", "")).lower() for m in messages)

    def test_returns_original_on_parse_error(
        self, monkeypatch: pytest.MonkeyPatch, evo_prompts: Any
    ) -> None:
        monkeypatch.setattr(
            "opik_optimizer.core.llm_calls.call_model",
            make_fake_llm_call("Not valid JSON at all"),
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

    def test_triggers_radical_innovation_randomly(
        self, monkeypatch: pytest.MonkeyPatch, evo_prompts: Any
    ) -> None:
        rng = force_random(monkeypatch, random_value=0.05)

        def fake_radical_innovation(**kwargs: Any) -> ChatPrompt:
            _ = kwargs
            return ChatPrompt(system="Radical innovation", user="New")

        monkeypatch.setattr(
            "opik_optimizer.algorithms.evolutionary_optimizer.ops.mutation_ops._radical_innovation_mutation",
            fake_radical_innovation,
        )

        prompt = ChatPrompt(system="Original", user="Question")

        result = mutation_ops._semantic_mutation(
            prompt=prompt,
            initial_prompt=prompt,
            model="gpt-4",
            model_parameters={},
            verbose=0,
            output_style_guidance="Be concise",
            prompts=evo_prompts,
            rng=rng,
        )

        assert isinstance(result, ChatPrompt)

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
            "opik_optimizer.algorithms.evolutionary_optimizer.ops.mutation_ops.reporting.display_error",
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


def test_semantic_mutation_invalid_json_response(
    monkeypatch: pytest.MonkeyPatch,
    evo_prompts: Any,
) -> None:
    def fake_call_model(
        *,
        messages: list[dict[str, str]],
        is_reasoning: bool,
        model: str,
        model_parameters: dict[str, Any],
        **_kwargs: Any,
    ) -> str:
        _ = (messages, is_reasoning, model, model_parameters)
        # Intentionally return a *non-JSON* python-literal-like string (single quotes)
        # to exercise the fallback parsing logic.
        return repr(
            [
                system_message("Provide a brief and direct answer to the question."),
                user_message("{question}"),
            ]
        )

    monkeypatch.setattr("opik_optimizer.core.llm_calls.call_model", fake_call_model)

    rng = force_random(monkeypatch, random_value=0.5)
    monkeypatch.setattr(
        "opik_optimizer.algorithms.evolutionary_optimizer.helpers.get_task_description_for_llm",
        lambda initial_prompt, optimize_tools=False: "Summarize task",
    )

    captured: dict[str, object] = {}

    def fake_display_error(message: str, verbose: int = 1) -> None:
        captured["message"] = message
        captured["verbose"] = verbose

    monkeypatch.setattr(
        "opik_optimizer.algorithms.evolutionary_optimizer.ops.mutation_ops.reporting.display_error",
        fake_display_error,
    )

    original_prompt = opik_optimizer.ChatPrompt(
        messages=[
            system_message("Provide factual answers."),
            user_message("What is the capital of France?"),
        ]
    )

    result = mutation_ops._semantic_mutation(
        prompt=original_prompt,
        initial_prompt=original_prompt,
        output_style_guidance="Keep answers brief.",
        model="openai/gpt-5-mini",
        model_parameters={},
        verbose=1,
        prompts=evo_prompts,
        rng=rng,
    )

    assert result is not original_prompt
    assert captured == {}
    assert result.get_messages() == [
        system_message("Provide a brief and direct answer to the question."),
        user_message("{question}"),
    ]

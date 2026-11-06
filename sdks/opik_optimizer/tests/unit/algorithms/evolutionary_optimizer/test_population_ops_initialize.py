"""Tests for evolutionary optimizer initialize_population()."""

from __future__ import annotations

from collections.abc import Callable
from typing import Any
from unittest.mock import MagicMock

import pytest

from opik_optimizer import ChatPrompt
from opik_optimizer.algorithms.evolutionary_optimizer.types import PopulationResponse
from opik_optimizer.algorithms.evolutionary_optimizer.ops.population_ops import (
    initialize_population,
)
from tests.unit.fixtures import system_message, user_message
from tests.unit.test_helpers import make_fake_llm_call


def _initialize_population_with_defaults(
    *,
    prompt: ChatPrompt,
    evo_prompts: Any,
    population_size: int,
) -> list[ChatPrompt]:
    """Call initialize_population() with the test module's standard defaults."""
    return initialize_population(
        prompt=prompt,
        output_style_guidance="Be concise",
        model="gpt-4",
        model_parameters={},
        prompts=evo_prompts,
        optimization_id="opt-123",
        population_size=population_size,
        verbose=0,
    )


def _fresh_response(*, system_text: str, user_template: str) -> PopulationResponse:
    return _variation_response(system_texts=[system_text], user_template=user_template)


def _variation_response(
    *, system_texts: list[str], user_template: str
) -> PopulationResponse:
    return PopulationResponse.model_validate(
        {
            "prompts": [
                {
                    "edits": [
                        {"segment_id": "message:0", "text": text},
                        {"segment_id": "message:1", "text": user_template},
                    ]
                }
                for text in system_texts
            ]
        }
    )


def _system_contents(population: list[ChatPrompt]) -> set[str]:
    contents: set[str] = set()
    for prompt in population:
        for msg in prompt.get_messages():
            if msg.get("role") == "system" and isinstance(msg.get("content"), str):
                contents.add(msg["content"])
    return contents


@pytest.fixture
def initializing_population_context(monkeypatch: pytest.MonkeyPatch) -> MagicMock:
    """Patch the reporting context manager used by initialize_population()."""
    mock_report = MagicMock()
    mock_context = MagicMock()
    mock_context.__enter__ = MagicMock(return_value=mock_report)
    mock_context.__exit__ = MagicMock(return_value=False)

    monkeypatch.setattr(
        "opik_optimizer.algorithms.evolutionary_optimizer.ops.population_ops.reporting.initializing_population",
        lambda verbose=0, **_kwargs: mock_context,
    )
    return mock_report


class TestInitializePopulation:
    def test_handles_llm_error_for_fresh_starts(
        self,
        monkeypatch: pytest.MonkeyPatch,
        evo_prompts: Any,
        initializing_population_context: MagicMock,
    ) -> None:
        _ = initializing_population_context

        monkeypatch.setattr(
            "opik_optimizer.core.llm_calls.call_model",
            make_fake_llm_call(raises=Exception("LLM API error")),
        )

        prompt = ChatPrompt(system="Original prompt", user="{input}")

        result = _initialize_population_with_defaults(
            prompt=prompt, evo_prompts=evo_prompts, population_size=5
        )

        assert result[0] is prompt

    def test_deduplicates_population(
        self,
        evo_prompts: Any,
        initializing_population_context: MagicMock,
        mock_llm_sequence: Callable[[list[Any]], dict[str, Any]],
    ) -> None:
        _ = initializing_population_context

        same_messages = [system_message("Same content"), user_message("{q}")]
        mock_llm_sequence(
            [
                _fresh_response(system_text="Same content", user_template="{q}"),
                _variation_response(
                    system_texts=["Same content", "Same content"], user_template="{q}"
                ),
            ]
        )

        prompt = ChatPrompt(messages=same_messages)

        result = _initialize_population_with_defaults(
            prompt=prompt, evo_prompts=evo_prompts, population_size=5
        )

        assert len(result) == 1

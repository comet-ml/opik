"""Rewriting a multi-text-part message must not leave empty text parts behind."""

from __future__ import annotations

import json
from typing import Any

import pytest

from opik_optimizer.agents.litellm_agent import LiteLLMAgent
from tests.unit.characterization import harness

EMPTY_TEXT_PART = '{"type": "text", "text": ""}'


def test_rewritten_prompts_and_agent_calls_have_no_empty_text_parts(
    install_fakes: Any, monkeypatch: pytest.MonkeyPatch
) -> None:
    scenario = harness.build_scenario("S3b_multimodal_multitext")
    install_fakes(scenario)
    agent_calls: list[str] = []
    original_complete = LiteLLMAgent._llm_complete

    def complete(
        self: Any, model: Any, messages: Any, *args: Any, **kwargs: Any
    ) -> Any:
        agent_calls.append(json.dumps(messages))
        return original_complete(self, model, messages, *args, **kwargs)

    monkeypatch.setattr(LiteLLMAgent, "_llm_complete", complete)

    result = harness.run_optimizer("EvolutionaryOptimizer", scenario)

    assert agent_calls
    assert not [call for call in agent_calls if EMPTY_TEXT_PART in call]
    assert EMPTY_TEXT_PART not in json.dumps(result.prompt.get_messages())

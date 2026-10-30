"""Fixtures for the characterization tests (no network, no real LLM)."""

from __future__ import annotations

import random
from collections.abc import Callable, Iterator
from typing import Any

import numpy as np
import opik
import pytest

from tests.unit.characterization import fake_llm, fake_opik
from tests.unit.characterization.harness import (
    TOOL_DESCRIPTION,
    TOOL_NAME,
    Scenario,
    base_prompt_messages,
)


def _forbid_network(*_args: Any, **_kwargs: Any) -> Any:
    raise AssertionError("characterization tests must not reach litellm")


@pytest.fixture
def install_fakes(
    monkeypatch: pytest.MonkeyPatch,
) -> Iterator[Callable[[Scenario], fake_llm.FakeOptimizerLLM]]:
    """Patch the LLM, agent and Opik boundaries; return an installer per scenario."""
    previous_tracing = opik.is_tracing_active()
    opik.set_tracing_active(False)
    random.seed(0)
    np.random.seed(0)

    def _install(scenario: Scenario) -> fake_llm.FakeOptimizerLLM:
        fake = fake_llm.FakeOptimizerLLM(
            base_prompts=base_prompt_messages(scenario),
            base_tools={TOOL_NAME: TOOL_DESCRIPTION} if scenario.tools else None,
            flatten_multimodal=scenario.flatten_multimodal,
        )
        client = fake_opik.make_opik_client()
        monkeypatch.setattr("opik.Opik", lambda *a, **kw: client)
        monkeypatch.setattr(
            "opik_optimizer.core.evaluation.opik_evaluator.evaluate",
            fake_opik.fake_evaluate,
        )
        monkeypatch.setattr(
            "opik_optimizer.core.evaluation.opik_evaluator.evaluate_optimization_trial",
            fake_opik.fake_evaluate,
        )
        monkeypatch.setattr("opik_optimizer.core.llm_calls.call_model", fake.call_model)
        monkeypatch.setattr(
            "opik_optimizer.core.llm_calls.call_model_async", fake.call_model_async
        )
        monkeypatch.setattr(
            "opik_optimizer.agents.litellm_agent.LiteLLMAgent._llm_complete",
            fake_llm.fake_llm_complete,
        )
        monkeypatch.setattr("litellm.completion", _forbid_network)
        monkeypatch.setattr("litellm.acompletion", _forbid_network)
        return fake

    try:
        yield _install
    finally:
        opik.set_tracing_active(previous_tracing)

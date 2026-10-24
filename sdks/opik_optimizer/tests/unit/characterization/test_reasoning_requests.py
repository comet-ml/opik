"""Pin what optimizers send to their reasoning LLM (no labels, no raw media)."""

from __future__ import annotations

import json
from typing import Any

import pytest

from opik_optimizer.core import llm_calls
from tests.unit.characterization import harness


def _capture_reasoning_calls(monkeypatch: pytest.MonkeyPatch) -> list[str]:
    """Record every reasoning request (sync and async) as serialized messages."""
    calls: list[str] = []
    original_call, original_async = llm_calls.call_model, llm_calls.call_model_async

    def call(**kwargs: Any) -> Any:
        calls.append(json.dumps(kwargs["messages"], default=str))
        return original_call(**kwargs)

    async def call_async(**kwargs: Any) -> Any:
        calls.append(json.dumps(kwargs["messages"], default=str))
        return await original_async(**kwargs)

    monkeypatch.setattr(llm_calls, "call_model", call)
    monkeypatch.setattr(llm_calls, "call_model_async", call_async)
    return calls


@pytest.mark.parametrize("scenario_name", ["S1_single", "S2_multi", "S4_tools"])
def test_meta_prompt_generation_requests_carry_no_dataset_labels(
    scenario_name: str, install_fakes: Any, monkeypatch: pytest.MonkeyPatch
) -> None:
    scenario = harness.build_scenario(scenario_name)
    for item in scenario.items:
        item["answer"] = "SECRET_" + item["answer"]
    install_fakes(scenario)
    calls = _capture_reasoning_calls(monkeypatch)

    harness.run_optimizer("MetaPromptOptimizer", scenario)

    assert calls
    assert not [call for call in calls if "SECRET_" in call]

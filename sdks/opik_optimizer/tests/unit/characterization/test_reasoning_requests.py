"""Pin what optimizers send to their reasoning LLM (no labels, no raw media)."""

from __future__ import annotations

import json
from typing import Any

import pytest

from opik_optimizer.core import llm_calls
from opik_optimizer.utils.multimodal import text_only_messages
from tests.unit.characterization import harness


def _capture_reasoning_calls(monkeypatch: pytest.MonkeyPatch) -> list[str]:
    """Record every reasoning request (sync and async) as serialized messages."""
    calls: list[str] = []
    original_call, original_async = llm_calls.call_model, llm_calls.call_model_async

    def call(**kwargs: Any) -> Any:
        calls.append(json.dumps(text_only_messages(kwargs["messages"]), default=str))
        return original_call(**kwargs)

    async def call_async(**kwargs: Any) -> Any:
        calls.append(json.dumps(text_only_messages(kwargs["messages"]), default=str))
        return await original_async(**kwargs)

    monkeypatch.setattr(llm_calls, "call_model", call)
    monkeypatch.setattr(llm_calls, "call_model_async", call_async)
    return calls


def test_meta_prompt_generation_requests_carry_no_dataset_labels(
    install_fakes: Any, monkeypatch: pytest.MonkeyPatch
) -> None:
    scenario = harness.build_scenario("S1_single")
    for item in scenario.items:
        item["answer"] = "SECRET_" + item["answer"]
    install_fakes(scenario)
    calls = _capture_reasoning_calls(monkeypatch)
    harness.run_optimizer("MetaPromptOptimizer", scenario)

    assert calls
    assert not [call for call in calls if "SECRET_" in call]


def test_gepa_reflective_inputs_are_bounded_and_exclude_labels(
    install_fakes: Any, monkeypatch: pytest.MonkeyPatch
) -> None:
    from opik_optimizer.algorithms.gepa_optimizer.adapter import OpikGEPAAdapter

    scenario = harness.build_scenario("S1_single")
    for item in scenario.items:
        item["context"] = "X" * 20_000
    install_fakes(scenario)
    records: list[dict[str, Any]] = []
    original = OpikGEPAAdapter.make_reflective_dataset

    def capture(self: Any, *args: Any, **kwargs: Any) -> Any:
        dataset = original(self, *args, **kwargs)
        records.extend(r for rows in dataset.values() for r in rows)
        return dataset

    monkeypatch.setattr(OpikGEPAAdapter, "make_reflective_dataset", capture)

    harness.run_optimizer("GepaOptimizer", scenario)

    assert records
    for record in records:
        assert set(record["Inputs"]) == {"question", "context"}
        assert len(json.dumps(record["Inputs"])) < 1_000


INLINE_IMAGE = "data:image/png;base64," + "QUJD" * 500

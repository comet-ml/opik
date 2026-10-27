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


@pytest.mark.parametrize(
    "optimizer_name", [n for n in harness.OPTIMIZERS if n != "ParameterOptimizer"]
)
@pytest.mark.parametrize("roles", ["system", "all"])
def test_reasoning_text_carries_no_encoded_media(
    optimizer_name: str,
    roles: str,
    install_fakes: Any,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    scenario = harness.build_scenario("S3_multimodal")
    assert isinstance(scenario.prompt, harness.ChatPrompt)
    messages = scenario.prompt.get_messages()
    messages[0]["content"] = [
        {"type": "text", "text": "You read images and answer."},
        {"type": "image_url", "image_url": {"url": INLINE_IMAGE}},
    ]
    scenario.prompt.set_messages(messages)
    install_fakes(scenario)
    calls = _capture_reasoning_calls(monkeypatch)

    result = harness.make_optimizer(optimizer_name).optimize_prompt(
        prompt=scenario.prompt,
        dataset=harness.make_dataset(scenario),
        metric=harness.answer_match,
        max_trials=harness.MAX_TRIALS[optimizer_name],
        optimize_prompts=roles,
    )

    assert calls
    assert [call for call in calls if "base64," in call] == []
    final = json.dumps(result.prompt.get_messages())
    assert INLINE_IMAGE in final
    assert '"url": "{image}"' in final

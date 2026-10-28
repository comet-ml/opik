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


def _capture_meta_prompt_cases(monkeypatch: pytest.MonkeyPatch) -> list[list[Any]]:
    cases: list[list[Any]] = []
    original = llm_calls.call_model

    def call(**kwargs: Any) -> Any:
        evidence = kwargs.get("evidence")
        if evidence is not None and evidence.cases:
            cases.append(list(evidence.cases))
        return original(**kwargs)

    monkeypatch.setattr(llm_calls, "call_model", call)
    return cases


def test_meta_prompt_reasons_over_how_the_prompt_scored(
    install_fakes: Any, monkeypatch: pytest.MonkeyPatch
) -> None:
    # Inputs alone don't say why a prompt loses points; the outputs and metric
    # reasons do.
    scenario = harness.build_scenario("S1_single")
    install_fakes(scenario)
    requests = _capture_meta_prompt_cases(monkeypatch)

    harness.run_optimizer("MetaPromptOptimizer", scenario)

    assert requests
    for cases in requests:
        assert all(case.output is not None and case.scores for case in cases)
        values = [case.scores[0].value for case in cases]
        assert values == sorted(values)  # the failures come first


def test_meta_prompt_never_reasons_over_validation_scores(
    install_fakes: Any, monkeypatch: pytest.MonkeyPatch
) -> None:
    from tests.unit.test_helpers import make_mock_dataset

    scenario = harness.build_scenario("S1_single")
    install_fakes(scenario)
    requests = _capture_meta_prompt_cases(monkeypatch)
    validation = make_mock_dataset(
        scenario.items, name="characterization-validation", dataset_id="ds-validation"
    )

    harness.run_optimizer(
        "MetaPromptOptimizer", scenario, validation_dataset=validation
    )

    assert requests
    assert all(
        case.output is None and not case.scores for cases in requests for case in cases
    )

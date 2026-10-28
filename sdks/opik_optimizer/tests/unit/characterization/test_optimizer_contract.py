"""Behaviour every optimizer must share, exercised through its public entrypoint.

Each test runs the real optimizer end to end against the deterministic fakes:
the fake agent answers more items correctly as prompts gain hint sentences, so
scores move only when an optimizer actually improves a prompt.
"""

from __future__ import annotations

import copy
import json
from typing import Any

import opik
import pytest
from opik.evaluation.metrics import score_result

from opik_optimizer import ChatPrompt
from opik_optimizer.core import llm_calls
from opik_optimizer.core.exceptions import ScoringFailedError
from tests.unit.characterization import fake_opik, harness

PROMPT_OPTIMIZERS = [n for n in harness.OPTIMIZERS if n != "ParameterOptimizer"]


def _as_dict(prompt: ChatPrompt | dict[str, ChatPrompt]) -> dict[str, ChatPrompt]:
    return prompt if isinstance(prompt, dict) else {prompt.name: prompt}


def _perfect(dataset_item: dict[str, Any], llm_output: str) -> score_result.ScoreResult:
    return score_result.ScoreResult(name="perfect", value=1.0, reason="ok")


def _flat(dataset_item: dict[str, Any], llm_output: str) -> score_result.ScoreResult:
    return score_result.ScoreResult(name="flat", value=0.5, reason="no signal")


def _broken_judge(dataset_item: dict[str, Any], llm_output: str) -> Any:
    raise RuntimeError("judge offline")


def _final_status() -> str:
    # install_fakes makes opik.Opik() return one shared fake client.
    update = opik.Opik().create_optimization.return_value.update
    return update.call_args.kwargs["status"]


@pytest.mark.parametrize("scenario_name", ["S1_single", "S2_multi"])
@pytest.mark.parametrize("optimizer_name", harness.OPTIMIZERS)
def test_improves_the_score_without_touching_the_callers_prompt(
    optimizer_name: str, scenario_name: str, install_fakes: Any
) -> None:
    scenario = harness.build_scenario(scenario_name)
    install_fakes(scenario)
    before = {
        name: copy.deepcopy(prompt.get_messages())
        for name, prompt in _as_dict(scenario.prompt).items()
    }

    result = harness.run_optimizer(optimizer_name, scenario)

    if optimizer_name == "ParameterOptimizer" and scenario.agent is not None:
        # The custom agent ignores sampling settings, so there is nothing to gain.
        assert result.score >= result.initial_score
    else:
        assert result.score > result.initial_score
    assert result.details["trials_completed"] <= harness.MAX_TRIALS[optimizer_name]
    assert result.details["finish_reason"] in {
        "max_trials",
        "perfect_score",
        "completed",
    }
    assert result.history
    assert _final_status() == "completed"
    assert type(result.prompt) is type(scenario.prompt)
    returned = _as_dict(result.prompt)
    assert set(returned) == set(before)
    for name, messages in before.items():
        assert _as_dict(scenario.prompt)[name].get_messages() == messages
        optimized = returned[name].get_messages()
        # Only system messages are optimized by default; the task input survives.
        assert [m for m in optimized if m["role"] == "user"] == [
            m for m in messages if m["role"] == "user"
        ]
        if optimizer_name == "ParameterOptimizer":
            assert optimized == messages


@pytest.mark.parametrize("optimizer_name", harness.OPTIMIZERS)
def test_trial_experiments_record_the_candidate_tree(
    optimizer_name: str, install_fakes: Any, monkeypatch: pytest.MonkeyPatch
) -> None:
    # Opik draws the search tree and labels trials Passed/Discarded from these
    # keys; without them it chains trials in creation order.
    scenario = harness.build_scenario("S1_single")
    install_fakes(scenario)
    configs: list[dict[str, Any]] = []

    def _recording_trial(**kwargs: Any) -> Any:
        if kwargs.get("experiment_type", "trial") == "trial":
            configs.append(kwargs["experiment_config"])
        return fake_opik.fake_evaluate(**kwargs)

    monkeypatch.setattr(
        "opik_optimizer.core.evaluation.opik_evaluator.evaluate_optimization_trial",
        _recording_trial,
    )

    result = harness.run_optimizer(optimizer_name, scenario)

    assert result.score > result.initial_score
    assert configs[0]["candidate_id"] == "baseline"
    seen: dict[str, tuple[int, list[str]]] = {}
    for config in configs:
        node = (config["step_index"], config["parent_candidate_ids"])
        candidate_id = config["candidate_id"]
        if candidate_id in seen:
            assert seen[candidate_id] == node  # re-evaluations keep their place
            continue
        step_index, parent_ids = node
        assert all(parent in seen for parent in parent_ids)
        expected_step = 1 + max((seen[p][0] for p in parent_ids), default=-1)
        assert step_index == expected_step
        seen[candidate_id] = node
    assert len(seen) > 1
    assert all(parents for cid, (_, parents) in seen.items() if cid != "baseline")


@pytest.mark.parametrize("optimizer_name", harness.OPTIMIZERS)
def test_perfect_baseline_returns_it_without_searching(
    optimizer_name: str, install_fakes: Any
) -> None:
    scenario = harness.build_scenario("S1_single")
    fake = install_fakes(scenario)

    result = harness.run_optimizer(optimizer_name, scenario, metric=_perfect)

    assert result.score == result.initial_score == 1.0
    assert result.details["finish_reason"] == "perfect_score"
    assert result.details["trials_completed"] == 1
    assert isinstance(result.prompt, ChatPrompt)
    assert isinstance(scenario.prompt, ChatPrompt)
    assert result.prompt.get_messages() == scenario.prompt.get_messages()
    assert not fake.calls


@pytest.mark.parametrize("optimizer_name", harness.OPTIMIZERS)
def test_stops_once_a_candidate_reaches_the_perfect_score(
    optimizer_name: str, install_fakes: Any
) -> None:
    scenario = harness.build_scenario("S1_single")
    install_fakes(scenario)
    optimizer = harness.make_optimizer(optimizer_name)
    optimizer.perfect_score = 0.45  # baseline answers 2/6 items; one hint is enough

    result = harness.run_optimizer(optimizer_name, scenario, optimizer=optimizer)

    assert result.initial_score < 0.45 <= result.score
    assert result.details["finish_reason"] == "perfect_score"
    assert result.details["trials_completed"] < harness.MAX_TRIALS[optimizer_name]


@pytest.mark.parametrize("optimizer_name", harness.OPTIMIZERS)
def test_returns_the_original_prompt_when_nothing_beats_it(
    optimizer_name: str, install_fakes: Any
) -> None:
    scenario = harness.build_scenario("S1_single")
    install_fakes(scenario)

    result = harness.run_optimizer(
        optimizer_name, scenario, metric=_flat, max_trials=20
    )

    assert result.score == result.initial_score == 0.5
    assert isinstance(result.prompt, ChatPrompt)
    assert isinstance(scenario.prompt, ChatPrompt)
    assert result.prompt.get_messages() == scenario.prompt.get_messages()
    assert result.details["trials_completed"] <= 20
    if optimizer_name in {"GepaOptimizer", "HierarchicalReflectiveOptimizer"}:
        # These detect convergence and stop before spending the budget.
        assert result.details["finish_reason"] == "no_improvement"
        assert result.details["trials_completed"] < 20


@pytest.mark.parametrize("optimizer_name", harness.OPTIMIZERS)
def test_a_metric_that_never_scores_fails_the_run(
    optimizer_name: str, install_fakes: Any
) -> None:
    scenario = harness.build_scenario("S1_single")
    install_fakes(scenario)

    with pytest.raises(ScoringFailedError, match="failed to score 6 of 6"):
        harness.run_optimizer(optimizer_name, scenario, metric=_broken_judge)

    # Otherwise the run shows as "running" in Opik forever.
    assert _final_status() == "error"


@pytest.mark.parametrize("optimizer_name", harness.OPTIMIZERS)
def test_validation_dataset_is_what_gets_scored(
    optimizer_name: str, install_fakes: Any, monkeypatch: pytest.MonkeyPatch
) -> None:
    scenario = harness.build_scenario("S1_single")
    install_fakes(scenario)
    evaluated: list[str] = []

    def recording_evaluate(**kwargs: Any) -> Any:
        evaluated.append(kwargs["dataset"].name)
        return fake_opik.fake_evaluate(**kwargs)

    for target in ("evaluate", "evaluate_optimization_trial"):
        monkeypatch.setattr(
            f"opik_optimizer.core.evaluation.opik_evaluator.{target}",
            recording_evaluate,
        )
    validation = harness.make_mock_dataset(
        [dict(item, id=f"val-{item['id']}") for item in scenario.items[:3]],
        name="validation-dataset",
        dataset_id="ds-validation",
    )

    harness.run_optimizer(optimizer_name, scenario, validation_dataset=validation)

    assert "validation-dataset" in evaluated


@pytest.mark.parametrize("optimizer_name", harness.OPTIMIZERS)
def test_validation_items_never_reach_the_optimizer_model(
    optimizer_name: str, install_fakes: Any, monkeypatch: pytest.MonkeyPatch
) -> None:
    scenario = harness.build_scenario("S1_single")
    for item in scenario.items:
        item["question"] = "TRAIN " + item["question"]
    install_fakes(scenario)
    validation = harness.make_mock_dataset(
        [
            dict(item, id=f"val-{item['id']}", question=f"HELDOUT {item['question']}")
            for item in scenario.items
        ],
        name="validation-dataset",
        dataset_id="ds-validation",
    )
    requests: list[str] = []
    fake_sync, fake_async = llm_calls.call_model, llm_calls.call_model_async

    def capture(kwargs: dict[str, Any]) -> None:
        # The request the real client sends, after it attaches evidence cases.
        sent = llm_calls._prepare_reasoning_request(
            kwargs.get("messages") or [], kwargs.get("evidence"), harness.MODEL
        )
        requests.append(json.dumps(sent, default=str))

    def sync_call(**kwargs: Any) -> Any:
        capture(kwargs)
        return fake_sync(**kwargs)

    async def async_call(**kwargs: Any) -> Any:
        capture(kwargs)
        return await fake_async(**kwargs)

    monkeypatch.setattr(llm_calls, "call_model", sync_call)
    monkeypatch.setattr(llm_calls, "call_model_async", async_call)

    harness.run_optimizer(optimizer_name, scenario, validation_dataset=validation)

    assert not [request for request in requests if "HELDOUT" in request]
    # Evolutionary reasons about prompts only; Parameter makes no reasoning calls.
    if optimizer_name not in {"EvolutionaryOptimizer", "ParameterOptimizer"}:
        assert any("TRAIN" in request for request in requests)


@pytest.mark.parametrize("optimizer_name", harness.OPTIMIZERS)
def test_n_samples_scores_every_candidate_on_the_baseline_items(
    optimizer_name: str, install_fakes: Any, monkeypatch: pytest.MonkeyPatch
) -> None:
    scenario = harness.build_scenario("S1_single")
    install_fakes(scenario)
    evaluated: list[set[str]] = []

    def recording_evaluate(**kwargs: Any) -> Any:
        result = fake_opik.fake_evaluate(**kwargs)
        evaluated.append({r.test_case.dataset_item_id for r in result.test_results})
        return result

    for target in ("evaluate", "evaluate_optimization_trial"):
        monkeypatch.setattr(
            f"opik_optimizer.core.evaluation.opik_evaluator.{target}",
            recording_evaluate,
        )

    # A minibatch the size of n_samples would be indistinguishable below.
    result = harness.run_optimizer(
        optimizer_name, scenario, n_samples=2, n_samples_minibatch=1
    )

    baseline_items = evaluated[0]
    assert len(baseline_items) == 2
    # Minibatches may differ; every full-size evaluation uses the baseline items.
    assert all(items == baseline_items for items in evaluated if len(items) == 2)
    if result.details["finish_reason"] == "perfect_score":
        assert result.score >= harness.make_optimizer(optimizer_name).perfect_score


@pytest.mark.parametrize("optimizer_name", harness.OPTIMIZERS)
def test_an_optimizer_instance_can_be_reused(
    optimizer_name: str, install_fakes: Any
) -> None:
    scenario = harness.build_scenario("S1_single")
    install_fakes(scenario)
    optimizer = harness.make_optimizer(optimizer_name)

    first = harness.run_optimizer(optimizer_name, scenario, optimizer=optimizer)
    second = harness.run_optimizer(optimizer_name, scenario, optimizer=optimizer)

    assert second.details["trials_completed"] == first.details["trials_completed"]
    assert second.score == first.score


@pytest.mark.parametrize("optimizer_name", harness.OPTIMIZERS)
def test_prompt_media_survives_optimization(
    optimizer_name: str, install_fakes: Any
) -> None:
    scenario = harness.build_scenario("S3_multimodal")
    install_fakes(scenario)
    assert isinstance(scenario.prompt, ChatPrompt)

    def task_media(prompt: ChatPrompt) -> list[dict[str, Any]]:
        # The last message is the task; FewShot may add image demonstrations before it.
        content = prompt.get_messages()[-1]["content"]
        return [part for part in content if part["type"] != "text"]

    media = task_media(scenario.prompt)

    result = harness.run_optimizer(optimizer_name, scenario)

    assert media and task_media(result.prompt) == media


@pytest.mark.parametrize("optimizer_name", harness.OPTIMIZERS)
def test_runs_in_the_dataset_project_without_warning_by_default(
    optimizer_name: str, install_fakes: Any, caplog: pytest.LogCaptureFixture
) -> None:
    scenario = harness.build_scenario("S1_single")
    install_fakes(scenario)
    dataset = harness.make_dataset(scenario)
    dataset.project_name = "dataset-project"
    optimizer = harness.make_optimizer(optimizer_name)

    harness.run_optimizer(
        optimizer_name,
        scenario,
        optimizer=optimizer,
        dataset=dataset,
        project_name=None,
    )

    assert optimizer.project_name == "dataset-project"
    assert "is ignored" not in caplog.text

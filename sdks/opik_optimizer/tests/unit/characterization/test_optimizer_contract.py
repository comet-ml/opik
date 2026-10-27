"""Behaviour every optimizer must share, exercised through its public entrypoint.

Each test runs the real optimizer end to end against the deterministic fakes:
the fake agent answers more items correctly as prompts gain hint sentences, so
scores move only when an optimizer actually improves a prompt.
"""

from __future__ import annotations

import copy
from typing import Any

import pytest
from opik.evaluation.metrics import score_result

from opik_optimizer import ChatPrompt
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

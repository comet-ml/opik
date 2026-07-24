"""OPIK-7038: ``select_best_trial`` must follow the strict tie policy so the
returned payload can never disagree with the ``reused_baseline`` flag."""

from __future__ import annotations

from typing import Any

import optuna
from optuna.trial import TrialState

from opik_optimizer.algorithms.parameter_optimizer.ops.optuna_ops import (
    select_best_trial,
)


def _trial(value: float, user_attrs: dict[str, Any]) -> optuna.trial.FrozenTrial:
    """Build a real completed ``FrozenTrial`` — ``select_best_trial`` reads its
    ``.value`` and ``.user_attrs``."""
    return optuna.trial.create_trial(
        state=TrialState.COMPLETE,
        value=value,
        user_attrs=user_attrs,
    )


def _baseline() -> tuple[float, dict[str, Any], dict[str, Any], dict[str, str]]:
    # Incumbent as initialized by the optimizer before any trial is adopted:
    # empty parameters, baseline model kwargs/model.
    return (
        0.5,
        {},
        {"p": {"temperature": 0.0}},
        {"p": "gpt-4o"},
    )


def test_select_best_trial__no_completed_trials__keeps_incumbent() -> None:
    incumbent = _baseline()
    assert (
        select_best_trial(
            completed_trials=[],
            best_score=incumbent[0],
            best_parameters=incumbent[1],
            best_model_kwargs=incumbent[2],
            best_model=incumbent[3],
        )
        == incumbent
    )


def test_select_best_trial__trial_strictly_beats_incumbent__adopts_trial() -> None:
    incumbent = _baseline()
    trial = _trial(
        value=0.9,
        user_attrs={
            "parameters": {"temperature": 0.7},
            "model_kwargs": {"p": {"temperature": 0.7}},
            "model": {"p": "gpt-4o"},
        },
    )

    score, parameters, model_kwargs, _ = select_best_trial(
        completed_trials=[trial],
        best_score=incumbent[0],
        best_parameters=incumbent[1],
        best_model_kwargs=incumbent[2],
        best_model=incumbent[3],
    )

    assert score == 0.9
    assert parameters == {"temperature": 0.7}
    assert model_kwargs == {"p": {"temperature": 0.7}}


def test_select_best_trial__trial_ties_incumbent__keeps_baseline_no_param_leak() -> (
    None
):
    """A tie must NOT leak the trial's parameters into the payload, otherwise
    ``optimized_parameters`` would contradict ``reused_baseline=True``."""
    incumbent = _baseline()
    tied_trial = _trial(
        value=0.5,  # exactly equal to the baseline score
        user_attrs={
            "parameters": {"temperature": 0.7},
            "model_kwargs": {"p": {"temperature": 0.7}},
            "model": {"p": "gpt-4o"},
        },
    )

    score, parameters, model_kwargs, model = select_best_trial(
        completed_trials=[tied_trial],
        best_score=incumbent[0],
        best_parameters=incumbent[1],
        best_model_kwargs=incumbent[2],
        best_model=incumbent[3],
    )

    assert score == 0.5
    assert parameters == {}
    assert model_kwargs == {"p": {"temperature": 0.0}}
    assert model == {"p": "gpt-4o"}


def test_select_best_trial__trial_worse_than_incumbent__keeps_incumbent() -> None:
    incumbent = _baseline()
    worse_trial = _trial(
        value=0.1,
        user_attrs={"parameters": {"temperature": 0.7}},
    )

    assert (
        select_best_trial(
            completed_trials=[worse_trial],
            best_score=incumbent[0],
            best_parameters=incumbent[1],
            best_model_kwargs=incumbent[2],
            best_model=incumbent[3],
        )
        == incumbent
    )

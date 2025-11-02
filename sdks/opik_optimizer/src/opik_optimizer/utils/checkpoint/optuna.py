"""
Optuna study serialization utilities for checkpointing.
"""

from __future__ import annotations

import json
from typing import Any, Dict, List

import optuna
from optuna.distributions import distribution_to_json, json_to_distribution
from optuna.trial import TrialState


def export_study(study: optuna.study.Study) -> dict[str, Any]:
    """
    Serialize an Optuna study into JSON-serializable structures.
    """
    trials_payload: List[Dict[str, Any]] = []
    for trial in study.trials:
        trials_payload.append(
            {
                "number": trial.number,
                "params": trial.params,
                "distributions": {
                    name: distribution_to_json(dist)
                    for name, dist in trial.distributions.items()
                },
                "user_attrs": trial.user_attrs,
                "system_attrs": trial.system_attrs,
                "state": trial.state.name,
                "value": trial.value,
                "values": trial.values,
                "datetime_start": trial.datetime_start.isoformat()
                if trial.datetime_start
                else None,
                "datetime_complete": trial.datetime_complete.isoformat()
                if trial.datetime_complete
                else None,
            }
        )

    return {
        "direction": study.direction.name,
        "study_name": study.study_name,
        "trials": trials_payload,
    }


def import_study(
    payload: dict[str, Any], *, sampler: optuna.samplers.BaseSampler | None = None
) -> optuna.study.Study:
    """
    Create an in-memory Optuna study from serialized payload.
    """
    direction = getattr(optuna.study.StudyDirection, payload.get("direction", "MAXIMIZE"))
    study = optuna.create_study(direction=direction, sampler=sampler)

    for trial_data in payload.get("trials", []):
        distributions = {
            name: json_to_distribution(dist_json)
            for name, dist_json in trial_data.get("distributions", {}).items()
        }
        state = getattr(TrialState, trial_data.get("state", "COMPLETE"))
        trial = optuna.trial.create_trial(
            number=trial_data.get("number"),
            params=trial_data.get("params", {}),
            distributions=distributions,
            value=trial_data.get("value"),
            values=trial_data.get("values"),
            user_attrs=trial_data.get("user_attrs", {}),
            system_attrs=trial_data.get("system_attrs", {}),
            state=state,
        )
        study.add_trial(trial)

    return study

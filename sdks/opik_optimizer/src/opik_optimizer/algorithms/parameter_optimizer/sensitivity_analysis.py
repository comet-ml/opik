"""Sensitivity analysis utilities for parameter optimization."""

from __future__ import annotations

import math
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from optuna.trial import Trial

    from .parameter_spec import ParameterSpec


def compute_sensitivity_from_trials(
    trials: list[Trial], specs: list[ParameterSpec]
) -> dict[str, float]:
    """
    Compute parameter sensitivity from completed trials.

    This function calculates a correlation-based sensitivity measure for each parameter
    by analyzing how changes in parameter values correlate with changes in the objective
    function values across trials.

    Args:
        trials: List of completed Optuna trials
        specs: List of parameter specifications

    Returns:
        Dictionary mapping parameter names to sensitivity scores (0.0 to 1.0)
    """
    sensitivities: dict[str, float] = {}

    for spec in specs:
        param_name = spec.name
        values: list[float] = []
        scores: list[float] = []

        for trial in trials:
            if trial.value is None:
                continue

            raw_value = trial.params.get(param_name)
            if isinstance(raw_value, bool):
                processed = float(int(raw_value))
            elif isinstance(raw_value, (int, float)):
                processed = float(raw_value)
            else:
                continue

            values.append(processed)
            scores.append(float(trial.value))

        if len(values) < 2 or len(set(values)) == 1:
            sensitivities[param_name] = 0.0
            continue

        mean_val = sum(values) / len(values)
        mean_score = sum(scores) / len(scores)

        cov = sum((v - mean_val) * (s - mean_score) for v, s in zip(values, scores))
        var_val = sum((v - mean_val) ** 2 for v in values)
        var_score = sum((s - mean_score) ** 2 for s in scores)

        if var_val <= 0 or var_score <= 0:
            sensitivities[param_name] = 0.0
            continue

        corr = abs(cov) / math.sqrt(var_val * var_score)
        sensitivities[param_name] = min(max(corr, 0.0), 1.0)

    return sensitivities

from __future__ import annotations

from opik_optimizer_framework.types import OptimizationState, TrialResult


def record_trial(state: OptimizationState, trial: TrialResult) -> None:
    """Record a completed trial into optimization state.

    Updates the best trial if this one has a higher score, and adds
    the config hash to seen_hashes for deduplication.
    """
    state.trials.append(trial)
    state.seen_hashes.add(trial.config_hash)

    if state.best_trial is None or trial.score > state.best_trial.score:
        state.best_trial = trial


def get_best(state: OptimizationState) -> TrialResult | None:
    """Return the best trial seen so far."""
    return state.best_trial


def get_sorted_trials(state: OptimizationState) -> list[TrialResult]:
    """Return all trials sorted by score descending."""
    return sorted(state.trials, key=lambda t: t.score, reverse=True)

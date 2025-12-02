"""Helpers for deriving optimizer budgets based on dataset rollout caps."""

from __future__ import annotations

from typing import Any

from benchmarks.core import benchmark_config


def derive_budgeted_optimize_params(
    dataset_name: str, optimizer_name: str
) -> dict[str, Any] | None:
    """Return a dict containing an inferred `max_trials` for the task.

    The calculation uses the train rollout budget when available (falling back
    to the total rollout budget) and divides it by the optimizer's default
    `n_samples` (if present). The result is clamped to the optimizer's default
    `max_trials` so we never exceed the baked-in defaults. When no budget is
    defined the function returns ``None`` so callers can fall back to optimizer
    defaults.
    """
    dataset_cfg = benchmark_config.DATASET_CONFIG.get(dataset_name)
    optimizer_cfg = benchmark_config.OPTIMIZER_CONFIGS.get(optimizer_name)
    if not dataset_cfg or not optimizer_cfg:
        return None

    rollout_budget = getattr(dataset_cfg, "train_rollout_budget", None) or getattr(
        dataset_cfg, "rollout_budget", None
    )
    if not rollout_budget:
        return None

    n_samples = optimizer_cfg.optimizer_prompt_params.get("n_samples")
    if n_samples and n_samples > 0:
        estimated_trials = rollout_budget // n_samples
    else:
        estimated_trials = rollout_budget

    default_max = optimizer_cfg.optimizer_prompt_params.get("max_trials")
    if default_max is not None:
        estimated_trials = min(default_max, estimated_trials)

    estimated_trials = max(1, int(estimated_trials))

    return {"max_trials": estimated_trials}


def resolve_optimize_params(
    dataset_name: str,
    optimizer_name: str,
    explicit_override: dict[str, Any] | None,
) -> dict[str, Any] | None:
    """Pick the explicit manifest settings or derive one from rollout budgets."""
    if explicit_override is not None:
        return explicit_override
    return derive_budgeted_optimize_params(dataset_name, optimizer_name)

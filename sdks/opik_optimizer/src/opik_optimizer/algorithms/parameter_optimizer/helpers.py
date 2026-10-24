"""Helper utilities for Parameter Optimizer.

Contains utility functions for building prompts, calculating trial counts,
and other parameter optimization helpers.
"""

from __future__ import annotations

import copy
from typing import Any

from ...api_objects import chat_prompt


def build_base_model_kwargs(
    model_parameters: dict[str, Any] | None,
) -> dict[str, Any]:
    """
    Copy agent model settings for parameter search.

    Removes 'n' parameter since parameter optimization evaluates
    a single candidate per trial.

    Args:
        model_parameters: Optional dict of parameters from an agent prompt

    Returns:
        Agent model kwargs with 'n' removed
    """
    base_kwargs = copy.deepcopy(model_parameters or {})
    base_kwargs.pop("n", None)
    return base_kwargs


def build_base_prompts(
    prompts: dict[str, chat_prompt.ChatPrompt],
) -> dict[str, chat_prompt.ChatPrompt]:
    """Copy each agent's settings, keeping evaluation single-choice during search."""
    base_prompts = {}
    for name, prompt in prompts.items():
        copied = prompt.copy()
        copied.model_kwargs = build_base_model_kwargs(prompt.model_kwargs)
        base_prompts[name] = copied
    return base_prompts


def calculate_trial_counts(
    max_trials: int | None,
    default_n_trials: int,
    local_search_ratio: float,
    local_trials_override: int | None = None,
) -> tuple[int, int, int]:
    """
    Calculate total, local, and global trial counts.

    Args:
        max_trials: Maximum trials (None to use default)
        default_n_trials: Default number of trials
        local_search_ratio: Ratio of trials for local search (0.0-1.0)
        local_trials_override: Optional override for local trials count

    Returns:
        Tuple of (total_trials, local_trials, global_trials)
    """
    total_trials = default_n_trials if max_trials is None else max_trials
    if total_trials < 0:
        total_trials = 0

    if local_trials_override is not None:
        local_trials = min(max(int(local_trials_override), 0), total_trials)
    else:
        local_trials = int(total_trials * local_search_ratio)

    global_trials = total_trials - local_trials
    if total_trials > 0 and global_trials <= 0:
        global_trials = 1
        local_trials = max(0, total_trials - global_trials)

    return total_trials, local_trials, global_trials

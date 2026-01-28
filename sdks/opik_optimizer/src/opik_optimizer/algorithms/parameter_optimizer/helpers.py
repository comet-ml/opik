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
    Build base model kwargs from optimizer's model parameters.

    Removes 'n' parameter since parameter optimization evaluates
    a single candidate per trial.

    Args:
        model_parameters: Optional dict of model parameters from optimizer

    Returns:
        Base model kwargs dict with 'n' removed
    """
    base_kwargs = copy.deepcopy(model_parameters or {})
    base_kwargs.pop("n", None)
    return base_kwargs


def build_base_prompts(
    prompts: dict[str, chat_prompt.ChatPrompt],
    model: str,
    base_model_kwargs: dict[str, Any],
) -> dict[str, chat_prompt.ChatPrompt]:
    """
    Build base prompts dict with model defaults applied.

    Merges optimizer's model parameters with each prompt's existing
    model_kwargs, ensuring the model is set and 'n' is removed.

    Args:
        prompts: Dictionary of prompt names to ChatPrompt instances
        model: Model name to set on all prompts
        base_model_kwargs: Base model kwargs (from build_base_model_kwargs)

    Returns:
        Dictionary of prompts with model and model_kwargs configured
    """
    base_prompts: dict[str, chat_prompt.ChatPrompt] = {}
    for name, p in prompts.items():
        base_p = p.copy()
        base_p.model = model
        merged_kwargs = {
            **base_model_kwargs,
            **copy.deepcopy(p.model_kwargs or {}),
        }
        # Keep per-trial evaluation single-choice until multi-candidate selection is added.
        merged_kwargs.pop("n", None)
        base_p.model_kwargs = merged_kwargs
        base_prompts[name] = base_p
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

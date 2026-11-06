"""Helper utilities for Parameter Optimizer.

Contains utility functions for building prompts, calculating trial counts,
and other parameter optimization helpers.
"""

from __future__ import annotations

import contextlib
import copy
import io
import warnings
from typing import Any, TYPE_CHECKING

import litellm

from ...api_objects import chat_prompt

if TYPE_CHECKING:
    from .ops.search_ops import ParameterSearchSpace


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


def _supported_params(model: str) -> list[str] | None:
    """LiteLLM's supported OpenAI params for a model, or None when it can't tell."""
    try:
        # LiteLLM prints a provider banner to stdout for unknown models.
        with contextlib.redirect_stdout(io.StringIO()):
            return litellm.get_supported_openai_params(model=model)
    except Exception:
        return None


def warn_unsupported_parameters(
    prompts: dict[str, chat_prompt.ChatPrompt],
    parameter_space: ParameterSearchSpace,
) -> None:
    """Warn about searched parameters the evaluated model doesn't support.

    BaseOptimizer sets litellm.drop_params, so such a parameter is silently dropped
    and every trial evaluates the same settings.
    """
    searched: list[tuple[str, str]] = []
    model_choices: dict[str, list[str]] = {}
    for spec in parameter_space.parameters:
        prompt_name = next((n for n in prompts if spec.name.startswith(f"{n}.")), None)
        if prompt_name is None:
            continue
        resolved = spec.target_path
        if spec.target is not None and resolved.root == "model":
            model_choices[prompt_name] = [str(c) for c in spec.choices or []]
        elif spec.target is None:
            searched.append((prompt_name, spec.name[len(prompt_name) + 1 :]))
        elif resolved.path:
            searched.append((prompt_name, resolved.path[0]))

    warned: set[tuple[str, str]] = set()
    for prompt_name, param in searched:
        models = model_choices.get(prompt_name) or [prompts[prompt_name].model]
        for model in models:
            if not model or (param, model) in warned:
                continue
            supported = _supported_params(model)
            if supported is None or param in supported:
                continue
            warned.add((param, model))
            warnings.warn(
                f"Parameter '{param}' is not supported by model '{model}' and will be "
                "dropped from every LiteLLM call, so trials varying it are identical. "
                "Remove it from the parameter space or use a model that supports it.",
                UserWarning,
                # Point at the caller of optimize_parameter (past its scope wrapper).
                stacklevel=4,
            )

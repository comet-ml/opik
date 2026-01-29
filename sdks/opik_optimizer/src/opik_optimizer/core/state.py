from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Literal, cast
from collections.abc import Sequence

from opik import Dataset
from opik.api_objects import optimization

from ..api_objects import chat_prompt
from ..api_objects.types import MetricFunction
from ..agents import OptimizableAgent
from .results import OptimizationRound
from ..utils.tool_helpers import (
    deep_merge_dicts,
    serialize_tools,
    summarize_tool_signatures,
)
from .. import helpers
from . import agent as agent_utils
from .. import constants

# Valid reasons for optimization to finish
FinishReason = Literal[
    "completed",  # Normal completion (all rounds/trials finished)
    "perfect_score",  # Target score threshold reached
    "max_trials",  # Maximum number of trials reached
    "no_improvement",  # No improvement for configured number of generations
    "error",  # Optimization failed
    "cancelled",  # Optimization cancelled/interrupted
]


@dataclass
class OptimizationContext:
    """
    Context object containing all state for an optimization run.

    The context travels through the entire lifecycle and is the single source of
    truth for optimization-wide flags and counters:
    - Input configuration: prompts, dataset(s), metric, agent, experiment config.
    - Runtime state: baseline_score, current_best_score, trials_completed,
      rounds_completed, in_progress span, etc.
    - Control flags: should_stop, finish_reason, perfect_score, max_trials.

    Optimizers should read/update the provided context instead of creating new
    ad-hoc counters so that budgeting, stop checks, and reporting stay aligned.
    BaseOptimizer enforces trial increments via evaluate(); callers should avoid
    manual increments except in adapter edge cases that already delegate through
    _should_stop_context.
    """

    prompts: dict[str, chat_prompt.ChatPrompt]
    initial_prompts: dict[str, chat_prompt.ChatPrompt]
    is_single_prompt_optimization: bool
    dataset: Dataset
    evaluation_dataset: Dataset
    validation_dataset: Dataset | None
    metric: MetricFunction
    agent: OptimizableAgent | None
    optimization: optimization.Optimization | None
    optimization_id: str | None
    experiment_config: dict[str, Any] | None
    n_samples: int | float | str | None
    max_trials: int
    project_name: str
    n_samples_minibatch: int | None = None
    n_samples_strategy: str = constants.DEFAULT_N_SAMPLES_STRATEGY
    allow_tool_use: bool = True
    baseline_score: float | None = None
    extra_params: dict[str, Any] = field(default_factory=dict)

    # Runtime state - set by evaluate()
    trials_completed: int = 0  # Number of evaluations completed
    should_stop: bool = False  # Flag to signal optimization should stop
    finish_reason: FinishReason | None = None  # Why optimization ended
    current_best_score: float | None = None  # Best score seen so far
    current_best_prompt: dict[str, chat_prompt.ChatPrompt] | None = (
        None  # Best prompt seen so far
    )
    dataset_split: str | None = None  # train/validation when applicable


@dataclass
class AlgorithmResult:
    """
    Simplified return type for optimizer algorithms.

    Optimizers return this from run_optimization() instead of building the full
    OptimizationResult themselves. BaseOptimizer wraps AlgorithmResult into an
    OptimizationResult by adding framework metadata (initial score, model info,
    IDs, counters) and performing any final normalization.

    Contract:
    - best_prompts: dict of prompt name -> ChatPrompt (even for single prompt).
    - best_score: primary objective score for the best prompts.
    - history: list of normalized round/trial dicts (or OptimizationRound/Trial)
      appended via OptimizationHistoryState; this is treated as authoritative.
    - metadata: algorithm-specific fields only (do not duplicate framework fields
      such as model, initial_score, finish_reason).

    Keeping this lightweight helps optimizers focus on algorithm logic while the
    framework handles user-facing output and wiring.
    """

    best_prompts: dict[str, chat_prompt.ChatPrompt]
    best_score: float
    history: Sequence[dict[str, Any] | OptimizationRound] = field(default_factory=list)
    metadata: dict[str, Any] = field(default_factory=dict)

    def __post_init__(self) -> None:
        if not isinstance(self.history, list):
            raise TypeError("AlgorithmResult.history must be a list of history entries")
        self.history = list(self.history)


def get_optimizer_metadata(optimizer: Any) -> dict[str, Any]:
    method = getattr(optimizer, "get_optimizer_metadata", None)
    if callable(method):
        return method()
    return {}


def build_optimizer_metadata(
    optimizer: Any, version: str | None = None
) -> dict[str, Any]:
    metadata = {
        "name": optimizer.__class__.__name__,
        "version": version or "unknown",
        "model": optimizer.model,
        "model_parameters": optimizer.model_parameters or None,
        "seed": getattr(optimizer, "seed", None),
        "num_threads": getattr(optimizer, "num_threads", None),
    }

    if metadata["num_threads"] is None and hasattr(optimizer, "n_threads"):
        metadata["num_threads"] = getattr(optimizer, "n_threads")

    if hasattr(optimizer, "reasoning_model"):
        metadata["reasoning_model"] = getattr(optimizer, "reasoning_model")

    extra_parameters = get_optimizer_metadata(optimizer)
    if extra_parameters:
        metadata["parameters"] = extra_parameters

    return helpers.drop_none(metadata)


def build_optimization_metadata(
    optimizer: Any, agent_class: type[OptimizableAgent] | None = None
) -> dict[str, Any]:
    metadata: dict[str, Any] = {"optimizer": optimizer.__class__.__name__}
    if getattr(optimizer, "name", None):
        metadata["name"] = optimizer.name

    agent_class_name: str | None = None
    if agent_class is not None:
        agent_class_name = getattr(agent_class, "__name__", None)
    elif hasattr(optimizer, "agent_class") and optimizer.agent_class is not None:
        agent_class_name = getattr(optimizer.agent_class, "__name__", None)

    if agent_class_name:
        metadata["agent_class"] = agent_class_name

    return metadata


def prepare_experiment_config(
    *,
    optimizer: Any,
    prompt: chat_prompt.ChatPrompt | dict[str, chat_prompt.ChatPrompt],
    dataset: Dataset,
    metric: MetricFunction,
    agent: OptimizableAgent | None,
    validation_dataset: Dataset | None,
    experiment_config: dict[str, Any] | None,
    configuration_updates: dict[str, Any] | None,
    additional_metadata: dict[str, Any] | None,
    is_single_prompt_optimization: bool,
    build_optimizer_version: str,
    training_dataset: Dataset | None = None,
) -> dict[str, Any]:
    project_name = optimizer.project_name

    # Always use dict format for consistency with candidate logging
    prompt_messages: dict[str, list[dict[str, Any]]]
    prompt_name: dict[str, str | None]
    prompt_project_name: dict[str, str | None]

    if isinstance(prompt, dict):
        first_prompt = next(iter(prompt.values()))
        agent_config = agent_utils.build_agent_config(
            optimizer=optimizer, prompt=first_prompt
        )
        tool_signatures = summarize_tool_signatures(first_prompt)

        # Always use dict format for consistency with candidate logging
        prompt_dict = cast(dict[str, chat_prompt.ChatPrompt], prompt)
        prompt_messages = {k: p.get_messages() for k, p in prompt_dict.items()}
        prompt_name = {k: getattr(p, "name", None) for k, p in prompt_dict.items()}
        prompt_project_name = {
            k: getattr(p, "project_name", None) for k, p in prompt_dict.items()
        }

        tools = serialize_tools(first_prompt)
    else:
        agent_config = agent_utils.build_agent_config(
            optimizer=optimizer, prompt=prompt
        )
        tool_signatures = summarize_tool_signatures(prompt)
        # Always use dict format for consistency with candidate logging
        prompt_name_attr = getattr(prompt, "name", "prompt")
        prompt_messages = {prompt_name_attr: prompt.get_messages()}
        prompt_name = {prompt_name_attr: getattr(prompt, "name", None)}
        tools = serialize_tools(prompt)
        prompt_project_name = {prompt_name_attr: getattr(prompt, "project_name", None)}

    # When using train/validation split, training_dataset identifies the training
    # dataset so we always set dataset_training/dataset_validation keys correctly
    # (OPIK-3820: avoid showing validation data under "dataset_training" keys).
    dataset_for_training_keys = (
        training_dataset if training_dataset is not None else dataset
    )
    base_config: dict[str, Any] = {
        "project_name": project_name,
        "agent_config": agent_config,
        "metric": metric.__name__,
        "dataset_training": dataset_for_training_keys.name,
        "dataset_training_id": dataset_for_training_keys.id,
        "optimizer": optimizer.__class__.__name__,
        "optimizer_metadata": build_optimizer_metadata(
            optimizer, build_optimizer_version
        ),
        "tool_signatures": tool_signatures,
        "configuration": {
            "prompt": prompt_messages,
            "prompt_name": prompt_name,
            "tools": tools,
            "prompt_project_name": prompt_project_name,
        },
    }

    if agent is not None:
        base_config["agent"] = agent.__class__.__name__

    if configuration_updates:
        base_config["configuration"] = deep_merge_dicts(
            base_config["configuration"], configuration_updates
        )

    if additional_metadata:
        base_config = deep_merge_dicts(base_config, additional_metadata)

    if experiment_config:
        base_config = deep_merge_dicts(base_config, experiment_config)

    if validation_dataset is not None:
        base_config["dataset_validation"] = validation_dataset.name
        base_config["dataset_validation_id"] = validation_dataset.id

    return base_config

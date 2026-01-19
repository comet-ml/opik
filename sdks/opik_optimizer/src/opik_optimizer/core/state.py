from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Literal
from collections.abc import Sequence
from contextvars import ContextVar

from opik import Dataset
from opik.api_objects import optimization

from ..api_objects import chat_prompt
from ..api_objects.types import MetricFunction
from ..agents import OptimizableAgent
from .results import OptimizationRound

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
    n_samples: int | None
    max_trials: int
    project_name: str
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


_CURRENT_CONTEXT: ContextVar[OptimizationContext | None] = ContextVar(
    "opik_optimizer_current_context",
    default=None,
)


def set_current_context(context: OptimizationContext | None) -> None:
    """Set the active optimization context for the current execution scope."""
    # TODO: Remove this once context is passed explicitly through all flows.
    _CURRENT_CONTEXT.set(context)


def get_current_context() -> OptimizationContext | None:
    """Return the active optimization context, if any."""
    return _CURRENT_CONTEXT.get()


def require_current_context() -> OptimizationContext:
    """Return the active optimization context or raise if not set."""
    # TODO: Replace this with explicit context passing in algorithm code.
    context = _CURRENT_CONTEXT.get()
    if context is None:
        raise RuntimeError("No active optimization context is set.")
    return context

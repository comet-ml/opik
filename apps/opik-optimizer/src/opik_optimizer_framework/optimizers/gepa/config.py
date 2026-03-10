"""Default configuration for the GEPA optimizer."""

from __future__ import annotations

from dataclasses import dataclass, fields
from typing import Any, Literal


@dataclass(frozen=True)
class GepaConfig:
    """Algorithm parameters for the GEPA optimizer.

    All fields have sensible defaults. Users can override any subset via
    ``optimizer_parameters`` in the optimization context.
    """

    seed: int = 42
    """Random seed for reproducibility across sampling and GEPA internals."""

    reflection_minibatch_size: int = 6
    """Number of items in the minibatch used for the reflection LLM and
    the acceptance gate. Larger = less noise but more expensive per iteration."""

    candidate_selection_strategy: Literal[
        "current_best", "pareto", "epsilon_greedy"
    ] = "current_best"
    """How to pick the parent candidate for the next mutation.

    ``"current_best"`` — always pick the highest-scoring candidate (fast convergence).
    ``"pareto"`` — frequency-weighted random from per-example Pareto front (more exploration).
    ``"epsilon_greedy"`` — best with prob 1-ε, random with prob ε.
    """

    max_candidates: int = 25
    """Maximum number of candidates in the pool before GEPA stops generating new ones."""

    max_metric_calls_multiplier: int = 5
    """``max_metric_calls = max_candidates * n_samples * max_metric_calls_multiplier``.
    Controls the total evaluation budget before the optimizer stops."""

    min_failed_per_batch: int = 1
    """Minimum number of failed items guaranteed in each minibatch.
    Ensures the reflection LLM always sees at least this many failures to fix."""

    score_threshold: float = 1.0
    """Stop optimization early when the best trial's pass_rate reaches this value."""

    persistent_failure_threshold: int = 7
    """Minimum cumulative assertion failures before showing Failure History to
    the reflection LLM. Lower values surface persistent failures earlier."""

    early_stopping_patience: int = 10
    """Stop optimization if the last N full evaluations show no pass_rate
    improvement. Set to 0 to disable early stopping."""

    @classmethod
    def from_params(cls, params: dict[str, Any]) -> GepaConfig:
        """Build config from optimizer_parameters, using defaults for missing keys."""
        known = {f.name for f in fields(cls)}
        return cls(**{k: v for k, v in params.items() if k in known})

"""Shared execution helpers for optimizer e2e tests."""

from __future__ import annotations

from typing import Any

from opik_optimizer import GepaOptimizer, ParameterOptimizer


def run_optimizer(
    *,
    optimizer_class: type,
    optimizer: Any,
    prompt: Any,
    dataset: Any,
    metric: Any,
    agent: Any | None = None,
    parameter_space: Any | None = None,
    n_samples: int = 1,
    max_trials: int = 1,
    **kwargs: Any,
) -> Any:
    """
    Run the appropriate optimization entrypoint for e2e tests.

    - ParameterOptimizer uses optimize_parameter (requires parameter_space)
    - GEPA needs a tiny reflection minibatch in CI-sized tests
    - Everything else uses optimize_prompt
    """
    extra_kwargs: dict[str, Any] = dict(kwargs)
    if (
        optimizer_class == GepaOptimizer
        and "reflection_minibatch_size" not in extra_kwargs
    ):
        extra_kwargs["reflection_minibatch_size"] = 1

    if optimizer_class == ParameterOptimizer:
        if parameter_space is None:
            raise ValueError(
                "parameter_space is required for ParameterOptimizer e2e runs"
            )
        return optimizer.optimize_parameter(
            prompt=prompt,
            dataset=dataset,
            metric=metric,
            parameter_space=parameter_space,
            agent=agent,
            n_samples=n_samples,
            max_trials=max_trials,
            **extra_kwargs,
        )

    return optimizer.optimize_prompt(
        prompt=prompt,
        dataset=dataset,
        metric=metric,
        agent=agent,
        n_samples=n_samples,
        max_trials=max_trials,
        **extra_kwargs,
    )

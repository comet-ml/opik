"""
Shared configuration helpers for optimizer e2e tests.

Keep e2e tests focused on behavior; put duplicated config/metric setup here.
"""

from __future__ import annotations

from typing import Any

from opik.evaluation.metrics import LevenshteinRatio
from opik.evaluation.metrics.score_result import ScoreResult
from opik_optimizer import (
    EvolutionaryOptimizer,
    FewShotBayesianOptimizer,
    GepaOptimizer,
    HierarchicalReflectiveOptimizer,
    MetaPromptOptimizer,
    ParameterOptimizer,
)
from opik_optimizer.algorithms.parameter_optimizer.ops.search_ops import (
    ParameterSearchSpace,
)


def levenshtein_metric(dataset_item: dict[str, Any], llm_output: str) -> ScoreResult:
    """
    Levenshtein ratio metric with reason (required by some optimizers).
    """
    metric = LevenshteinRatio()
    result = metric.score(reference=dataset_item["label"], output=llm_output)
    return ScoreResult(
        name=result.name,
        value=result.value,
        reason=f"Similarity: {result.value:.2f}",
    )


def create_optimizer_config(
    optimizer_class: type,
    *,
    max_tokens: int | None = None,
    verbose: int = 0,
) -> dict[str, Any]:
    """
    Create minimal optimizer configuration for fast e2e testing.
    """
    model_parameters: dict[str, Any] = {
        "temperature": 0.7,
    }
    if max_tokens is not None:
        model_parameters["max_tokens"] = max_tokens

    base_config: dict[str, Any] = {
        "model": "openai/gpt-5-nano",
        "model_parameters": model_parameters,
        "verbose": verbose,
        "seed": 42,
        "name": f"e2e-{optimizer_class.__name__}",
    }

    optimizer_specific: dict[type, dict[str, Any]] = {
        EvolutionaryOptimizer: {
            "population_size": 2,
            "num_generations": 1,
            "n_threads": 2,
            "enable_llm_crossover": False,
            "enable_moo": False,
            "elitism_size": 1,
        },
        MetaPromptOptimizer: {
            "n_threads": 2,
            "prompts_per_round": 1,
        },
        FewShotBayesianOptimizer: {
            "min_examples": 1,
            "max_examples": 2,
        },
        GepaOptimizer: {
            "n_threads": 2,
        },
        HierarchicalReflectiveOptimizer: {
            "n_threads": 2,
            "max_parallel_batches": 2,
            "batch_size": 2,
            "convergence_threshold": 0.01,
        },
        ParameterOptimizer: {
            "n_threads": 2,
            "default_n_trials": 1,
            "local_search_ratio": 0.0,
        },
    }

    return {**base_config, **optimizer_specific.get(optimizer_class, {})}


def get_parameter_space() -> ParameterSearchSpace:
    """Create a tiny parameter space for ParameterOptimizer e2e tests."""
    return ParameterSearchSpace.model_validate(
        {
            "temperature": {"type": "float", "min": 0.1, "max": 1.0},
        }
    )

from __future__ import annotations

import logging
from typing import Any

from opik_optimizer_framework.evaluation_adapter import EvaluationAdapter
from opik_optimizer_framework.event_emitter import LoggingEventEmitter, SdkEventEmitter
from opik_optimizer_framework.sampler import sample_split
from opik_optimizer_framework.types import (
    CandidateConfig,
    OptimizationContext,
    OptimizationResult,
    OptimizationState,
)

logger = logging.getLogger(__name__)

OPTIMIZER_REGISTRY: dict[str, type] = {}


def register_optimizer(name: str, optimizer_class: type) -> None:
    """Register an optimizer implementation by name."""
    OPTIMIZER_REGISTRY[name] = optimizer_class


def _register_builtins() -> None:
    from opik_optimizer_framework.optimizer.simple_optimizer import SimpleOptimizer
    register_optimizer("SimpleOptimizer", SimpleOptimizer)


_register_builtins()


def run_optimization(
    context: OptimizationContext,
    client: Any,
    dataset_item_ids: list[str],
    seed: int = 42,
) -> OptimizationResult:
    """Main entry point for running an optimization.

    Fetches dataset items, runs sampler, initializes all components,
    calls the optimizer, and returns the result.
    """
    logger.info("Starting optimization %s", context.optimization_id)

    # Sample train/validation split
    split = sample_split(dataset_item_ids, seed=seed)
    logger.info(
        "Split: %d train, %d validation",
        len(split.train_item_ids),
        len(split.validation_item_ids),
    )

    # Initialize state
    state = OptimizationState()

    # Initialize event emitter
    if client is not None:
        event_emitter = SdkEventEmitter(client, context.optimization_id)
    else:
        event_emitter = LoggingEventEmitter()

    # Initialize evaluation adapter
    adapter = EvaluationAdapter(
        client=client,
        dataset_name=context.dataset_name,
        optimization_id=context.optimization_id,
        metric_type=context.metric_type,
        metric_parameters=context.metric_parameters,
        state=state,
        event_emitter=event_emitter,
    )

    # Resolve optimizer
    optimizer_cls = OPTIMIZER_REGISTRY.get(context.optimizer_type)
    if optimizer_cls is None:
        available = ", ".join(sorted(OPTIMIZER_REGISTRY.keys()))
        raise ValueError(
            f"Unknown optimizer type: {context.optimizer_type!r}. "
            f"Available: [{available}]"
        )
    optimizer = optimizer_cls()

    # Evaluate baseline (original prompt)
    baseline_config = CandidateConfig(
        prompt_messages=context.prompt_messages,
        model=context.model,
        model_parameters=context.model_parameters,
    )
    baseline_trial = adapter.evaluate(
        config=baseline_config,
        dataset_item_ids=dataset_item_ids,
        step_index=-1,
    )
    initial_score = baseline_trial.score if baseline_trial else 0.0
    logger.info("Baseline score: %.4f", initial_score)

    # Run optimization
    try:
        optimizer.run(
            context=context,
            training_set=split.train_item_ids,
            validation_set=split.validation_item_ids,
            evaluation_adapter=adapter,
            state=state,
            event_emitter=event_emitter,
        )

        best = state.best_trial
        score = best.score if best else 0.0
        result = OptimizationResult(
            best_trial=best,
            all_trials=list(state.trials),
            score=score,
            initial_score=initial_score,
        )

        state.status = "completed"
        logger.info("Optimization %s completed. Score: %.4f", context.optimization_id, result.score)
        return result
    except Exception:
        state.status = "error"
        logger.exception("Optimization %s failed", context.optimization_id)
        raise

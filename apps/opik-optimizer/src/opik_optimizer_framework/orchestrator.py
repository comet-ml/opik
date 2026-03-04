from __future__ import annotations

import logging
from typing import Any

from opik_optimizer_framework.evaluation_adapter import EvaluationAdapter
from opik_optimizer_framework.event_emitter import EventEmitter
from opik_optimizer_framework.optimizers.factory import create_optimizer
from opik_optimizer_framework.sampler import sample_split
from opik_optimizer_framework.types import (
    OptimizationContext,
    OptimizationResult,
    OptimizationState,
)

logger = logging.getLogger(__name__)



def run_optimization(
    context: OptimizationContext,
    client: Any,
    dataset_items: list[dict[str, Any]],
    seed: int = 42,
) -> OptimizationResult:
    """Main entry point for running an optimization."""
    logger.info("Starting optimization %s", context.optimization_id)

    items_by_id: dict[str, dict[str, Any]] = {
        str(item["id"]): item for item in dataset_items
    }
    dataset_item_ids = list(items_by_id.keys())

    split = sample_split(dataset_item_ids, seed=seed)
    logger.info(
        "Split: %d train, %d validation",
        len(split.train_item_ids),
        len(split.validation_item_ids),
    )

    state = OptimizationState()

    event_emitter = EventEmitter(optimization_id=context.optimization_id)

    adapter = EvaluationAdapter(
        client=client,
        dataset_name=context.dataset_name,
        optimization_id=context.optimization_id,
        metric_type=context.metric_type,
        metric_parameters=context.metric_parameters,
        state=state,
        event_emitter=event_emitter,
        optimizer_type=context.optimizer_type,
    )

    optimizer = create_optimizer(context.optimizer_type)

    # Evaluate baseline (original prompt)
    baseline_trial = adapter.evaluate(
        config=context.baseline_config,
        dataset_item_ids=dataset_item_ids,
        eval_purpose="baseline",
    )
    initial_score = baseline_trial.score if baseline_trial else 0.0
    logger.info("Baseline score: %.4f", initial_score)

    # Run optimization
    try:
        train_items = [items_by_id[id] for id in split.train_item_ids]
        val_items = [items_by_id[id] for id in split.validation_item_ids]

        optimizer.run(
            context=context,
            training_set=train_items,
            validation_set=val_items,
            evaluation_adapter=adapter,
            state=state,
            baseline_trial=baseline_trial,
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

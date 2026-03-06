"""GEPA optimizer for the optimization framework.

Delegates to the external ``gepa`` library (v0.1.0+) for the optimization loop.
The adapter bridges GEPA's evaluate/reflect calls to the framework's EvaluationAdapter.
Lifecycle events are received via GEPAProgressCallback for accurate phase/parent tracking.

Uses the same dataset for both training and validation (no split) and a custom
reflection prompt that encourages generalizable instructions.
"""

from __future__ import annotations

import logging
import random

from opik_optimizer_framework.evaluation_adapter import EvaluationAdapter
from opik_optimizer_framework.types import (
    OptimizationContext,
    OptimizationState,
    TrialResult,
)

from .failure_aware_sampler import FailureAwareBatchSampler
from .gepa_adapter import (
    DatasetItem,
    FrameworkGEPAAdapter,
    GEPAProgressCallback,
)
from .reflection_proposer import GENERALIZATION_REFLECTION_TEMPLATE

logger = logging.getLogger(__name__)


def _build_seed_candidate(baseline_config: dict, optimizable_keys: list[str]) -> dict[str, str]:
    return {
        k: str(v) for k, v in baseline_config.items()
        if k in optimizable_keys and isinstance(v, str)
    }


def _make_config_builder(baseline_config: dict, optimizable_keys: list[str]):
    def build(candidate: dict[str, str]) -> dict:
        return {**baseline_config, **candidate, "optimizable_keys": optimizable_keys}
    return build


class GepaOptimizer:
    """Optimizer that uses GEPA's genetic-Pareto algorithm.

    Uses GEPA v0.1.0's callback system for lifecycle event tracking.
    Passes the same dataset for both training and validation and a reflection
    prompt that encourages generalizable instructions.
    """

    def __init__(self) -> None:
        self.adapter: FrameworkGEPAAdapter | None = None

    def run(
        self,
        context: OptimizationContext,
        training_set: list[DatasetItem],
        validation_set: list[DatasetItem],
        evaluation_adapter: EvaluationAdapter,
        state: OptimizationState,
        baseline_trial: TrialResult | None = None,
    ) -> None:
        try:
            import gepa as _gepa
        except ImportError:
            raise ImportError(
                "The 'gepa' package is required for GepaOptimizer. "
                "Install it with: pip install 'gepa>=0.1.0'"
            )

        params = context.optimizer_parameters
        seed = params.get("seed", 42)
        reflection_minibatch_size = max(4, params.get("reflection_minibatch_size", 4))
        candidate_selection_strategy = params.get(
            "candidate_selection_strategy", "pareto"
        )
        max_candidates = params.get("max_candidates", 5)

        seed_candidate = _build_seed_candidate(context.baseline_config, context.optimizable_keys)

        # Combine train + val into a single dataset (no split).
        seen_ids: set[str] = set()
        all_items: list[DatasetItem] = []
        for item in training_set + validation_set:
            item_id = str(item.get("id", ""))
            if item_id not in seen_ids:
                seen_ids.add(item_id)
                all_items.append(item)

        effective_n_samples = max(len(all_items), 1)
        max_metric_calls = params.get(
            "max_metric_calls", max_candidates * effective_n_samples * 5
        )

        sampler = FailureAwareBatchSampler(
            minibatch_size=reflection_minibatch_size,
            min_failed_per_batch=params.get("min_failed_per_batch", 1),
            failure_threshold=params.get("failure_threshold", 1.0),
            rng=random.Random(seed),
        )

        config_builder = _make_config_builder(context.baseline_config, context.optimizable_keys)
        adapter = FrameworkGEPAAdapter(
            config_builder=config_builder,
            evaluation_adapter=evaluation_adapter,
            reflection_lm=context.model,
            reflection_prompt_template=GENERALIZATION_REFLECTION_TEMPLATE,
            batch_sampler=sampler,
            config_descriptions=context.config_descriptions,
        )
        self.adapter = adapter

        if baseline_trial is not None:
            adapter.register_baseline(seed_candidate, baseline_trial.candidate_id)

        callback = GEPAProgressCallback(adapter=adapter)

        score_threshold = params.get("score_threshold", 1.0)

        def _trial_score_stopper(_state):
            return adapter.best_full_eval_trial_score >= score_threshold

        stop_callbacks = [_trial_score_stopper]

        result = _gepa.optimize(
            seed_candidate=seed_candidate,
            trainset=all_items,
            valset=all_items,
            adapter=adapter,
            reflection_lm=context.model,
            candidate_selection_strategy=candidate_selection_strategy,
            batch_sampler=sampler,
            max_metric_calls=max_metric_calls,
            stop_callbacks=stop_callbacks,
            callbacks=[callback],
            skip_perfect_score=False,
            perfect_score=1,
            seed=seed,
            reflection_prompt_template=None,
            cache_evaluation=False,
        )

        logger.info(
            "GepaOptimizer finished: best_score=%.4f, %d trials recorded",
            result.best_score if hasattr(result, "best_score") else -1,
            len(state.trials),
        )

        problematic = adapter.get_problematic_items_summary()
        if problematic:
            logger.info("Problematic items (%d):", len(problematic))
            for item in problematic:
                logger.info(
                    "  item=%s streak=%d assertions=%s",
                    item["item_id"][:12],
                    item["failure_streak"],
                    item["failing_assertions"],
                )
            adapter._reflection_log.append({
                "type": "problematic_items_summary",
                "items": problematic,
            })

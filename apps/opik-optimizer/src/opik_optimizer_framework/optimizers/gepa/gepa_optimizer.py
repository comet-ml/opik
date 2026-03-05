"""GEPA (Genetic-Pareto) Optimizer for the new optimization framework.

Delegates to the external ``gepa`` library for the optimization loop.
The GEPA adapter calls the framework's EvaluationAdapter during the
internal loop so every candidate gets real scores from the evaluation suite.
"""

from __future__ import annotations

import logging
from typing import Any

from opik_optimizer_framework.evaluation_adapter import EvaluationAdapter
from opik_optimizer_framework.types import (
    OptimizationContext,
    OptimizationState,
    TrialResult,
)

from .gepa_adapter import (
    FrameworkGEPAAdapter,
    GEPAProgressCallback,
    build_data_insts,
    build_seed_candidate,
    infer_dataset_keys,
)

logger = logging.getLogger(__name__)


class GepaOptimizer:
    """Optimizer that uses GEPA's genetic-Pareto algorithm."""

    skip_baseline_eval = True

    def run(
        self,
        context: OptimizationContext,
        training_set: list[dict[str, Any]],
        validation_set: list[dict[str, Any]],
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
        reflection_minibatch_size = params.get("reflection_minibatch_size", 3)
        candidate_selection_strategy = params.get(
            "candidate_selection_strategy", "pareto"
        )
        max_candidates = params.get("max_candidates", 5)

        seed_candidate = build_seed_candidate(context.prompt_messages)

        input_key, output_key = infer_dataset_keys(training_set)

        train_insts = build_data_insts(training_set, input_key, output_key)
        val_insts = build_data_insts(validation_set, input_key, output_key)

        effective_n_samples = max(len(train_insts), 1)
        max_metric_calls = params.get(
            "max_metric_calls", max_candidates * effective_n_samples
        )

        adapter = FrameworkGEPAAdapter(
            base_messages=context.prompt_messages,
            baseline_config=context.baseline_config,
            evaluation_adapter=evaluation_adapter,
        )

        if baseline_trial is not None:
            adapter.register_baseline(seed_candidate, baseline_trial.candidate_id)

        callback = GEPAProgressCallback(adapter=adapter)

        _gepa.optimize(
            seed_candidate=seed_candidate,
            trainset=train_insts,
            valset=val_insts,
            adapter=adapter,
            reflection_lm=context.model,
            candidate_selection_strategy=candidate_selection_strategy,
            reflection_minibatch_size=reflection_minibatch_size,
            max_metric_calls=max_metric_calls,
            callbacks=[callback],
            seed=seed,
        )

        logger.info(
            "GepaOptimizer finished: %d trials recorded",
            len(state.trials),
        )

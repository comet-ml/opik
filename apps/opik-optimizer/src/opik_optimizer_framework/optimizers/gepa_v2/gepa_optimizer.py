"""GEPA v2 optimizer for the optimization framework.

Delegates to the external ``gepa`` library (v0.1.0+) for the optimization loop.
The adapter bridges GEPA's evaluate/reflect calls to the framework's EvaluationAdapter.
Lifecycle events are received via GEPAProgressCallback for accurate phase/parent tracking.

Uses the same dataset for both training and validation (no split) and a custom
reflection prompt that encourages generalizable instructions.
"""

from __future__ import annotations

import logging

from opik_optimizer_framework.evaluation_adapter import EvaluationAdapter
from opik_optimizer_framework.types import (
    OptimizationContext,
    OptimizationState,
    TrialResult,
)

from .gepa_adapter import (
    DatasetItem,
    FrameworkGEPAAdapter,
    GEPAProgressCallback,
    build_seed_candidate,
)

logger = logging.getLogger(__name__)

GENERALIZATION_REFLECTION_TEMPLATE = """\
I provided an assistant with the following instructions to perform a task for me:
```
<curr_param>
```

The following are examples of different task inputs provided to the assistant \
along with the assistant's response for each of them, and some feedback on how \
the assistant's response could be better:
```
<side_info>
```

Your task is to write a new instruction for the assistant.

Read the inputs carefully and identify the input format and infer detailed task \
description about the task I wish to solve with the assistant.

Read all the assistant responses and the corresponding feedback. Identify all \
niche and domain specific factual information about the task and include it in \
the instruction, as a lot of it may not be available to the assistant in the \
future. The assistant may have utilized a generalizable strategy to solve the \
task, if so, include that in the instruction as well.

IMPORTANT: The instruction must generalize well to unseen inputs. You may use \
examples from the feedback to illustrate patterns, but do NOT include \
instructions that are hyper-specific to individual data points (e.g., "if the \
user asks about order #12345, say X" or "when topic is exactly Y, respond with \
Z"). Focus on general strategies, domain knowledge, and response patterns that \
apply broadly. Specifics about the domain are fine; specifics about individual \
test cases are not.

Provide the new instructions within ``` blocks."""


class GepaV2Optimizer:
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
            from gepa.utils.stop_condition import ScoreThresholdStopper
        except ImportError:
            raise ImportError(
                "The 'gepa' package is required for GepaV2Optimizer. "
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
            "max_metric_calls", max_candidates * effective_n_samples
        )

        adapter = FrameworkGEPAAdapter(
            base_messages=context.prompt_messages,
            baseline_config=context.baseline_config,
            evaluation_adapter=evaluation_adapter,
            reflection_lm=context.model,
            reflection_prompt_template=GENERALIZATION_REFLECTION_TEMPLATE,
        )
        self.adapter = adapter

        if baseline_trial is not None:
            adapter.register_baseline(seed_candidate, baseline_trial.candidate_id)

        callback = GEPAProgressCallback(adapter=adapter)

        score_threshold = params.get("score_threshold", 1.0)
        stop_callbacks = [ScoreThresholdStopper(threshold=score_threshold)]

        result = _gepa.optimize(
            seed_candidate=seed_candidate,
            trainset=all_items,
            valset=all_items,
            adapter=adapter,
            reflection_lm=context.model,
            candidate_selection_strategy=candidate_selection_strategy,
            reflection_minibatch_size=reflection_minibatch_size,
            max_metric_calls=max_metric_calls,
            stop_callbacks=stop_callbacks,
            callbacks=[callback],
            skip_perfect_score=True,
            perfect_score=1,
            seed=seed,
            reflection_prompt_template=None,
            cache_evaluation=False,
        )

        logger.info(
            "GepaV2Optimizer finished: best_score=%.4f, %d trials recorded",
            result.best_score if hasattr(result, "best_score") else -1,
            len(state.trials),
        )

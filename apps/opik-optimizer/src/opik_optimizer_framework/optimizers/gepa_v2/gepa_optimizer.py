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
along with the assistant's response for each of them, and feedback showing \
which assertions PASSED and which FAILED:
```
<side_info>
```

Your task is to write an improved instruction for the assistant.

STEP 1 — DIAGNOSE: Read the FAILED assertions. Each one names a specific \
behavior the assistant's response was missing. Identify the *patterns* across \
failures — what categories of behavior keep failing?

STEP 2 — KEEP WHAT WORKS: Look at the PASSED assertions. The current \
instruction already produces these behaviors. Copy the specific rules from \
the current instruction that drive these successes into your new version \
verbatim, unless they directly conflict with a fix.

STEP 3 — WRITE RULES THAT MATCH THE ASSERTION: Read each failing assertion \
carefully. The assertion itself tells you how specific your rule needs to be:

- If the assertion checks for a SPECIFIC behavior (e.g., "includes a \
code example", "mentions the deadline"), write a rule specific enough to \
guarantee that behavior. \
Example: "When the user's question includes a code snippet, always \
include a corrected version in your response."

- If the assertion checks for a GENERAL quality (e.g., "clear and concise", \
"factually accurate"), write a broader rule with a clear boundary. \
Example: "Never state uncertain information as fact — say 'this may \
vary' instead of asserting a specific value."

The assistant is a language model that executes literal instructions. Abstract \
advice like "be empathetic" does NOT reliably produce the right behavior. \
Every rule must describe an observable action (what to say, what to include, \
what to avoid).

STEP 4 — GENERALIZE ACROSS INPUTS: Your rules must work for any input in \
this domain, not just the examples shown. Do NOT reference specific test \
inputs (e.g., "if the input is about topic X, say Y"). Instead, try to turn \
the pattern into a general trigger but always mind the balance between SPECIFIC and GENERAL \
(e.g., "when the user references a specific entity, always confirm it back in your response"). \

If the feedback reveals domain facts the assistant wouldn't know on its own \
(e.g., company policies, product details), include those facts as rules.

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
            "max_metric_calls", max_candidates * effective_n_samples * 5
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
            reflection_minibatch_size=reflection_minibatch_size,
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
            "GepaV2Optimizer finished: best_score=%.4f, %d trials recorded",
            result.best_score if hasattr(result, "best_score") else -1,
            len(state.trials),
        )

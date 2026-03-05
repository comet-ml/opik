"""GEPA v2 optimizer for the optimization framework.

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


GENERALIZATION_REFLECTION_TEMPLATE = """\
I provided an assistant with the following instructions to perform a task for me:
```
<curr_param>
```

The following are examples of different task inputs provided to the assistant \
along with the assistant's response for each of them, and feedback showing \
which assertions PASSED and which FAILED. \
Examples are sorted by priority — the ones with the most failures come first. \
Some examples include a "Failure History" section — these items have failed \
across multiple consecutive optimization rounds despite prior prompt changes:
```
<side_info>
```

Your task is to write an improved instruction for the assistant.

STEP 1 — DIAGNOSE: Read the FAILED assertions. Each one names a specific \
behavior the assistant's response was missing. Identify the *patterns* across \
failures — what categories of behavior keep failing?

STEP 2 — CHECK FAILURE HISTORY: If any example has a "Failure History" \
section, your previous prompt rules DID NOT fix it. Do NOT repeat the same \
type of rule. Instead:
- If the assertion asks for SPECIFIC details (e.g., "specific loyalty benefits", \
"mentions the deadline"), embed concrete domain facts, example phrases, or \
lookup instructions directly into the prompt — generic advice like "highlight \
benefits" will fail again.
- If the assertion asks for a BEHAVIOR PATTERN, restructure your approach \
entirely rather than adding another numbered rule.

STEP 3 — KEEP WHAT WORKS: Look at the PASSED assertions. The current \
instruction already produces these behaviors. Preserve the rules that drive \
these successes unless they directly conflict with a fix.

STEP 4 — WRITE TARGETED RULES: Read each failing assertion carefully. \
The assertion itself tells you how specific your rule needs to be:

- If the assertion checks for a SPECIFIC behavior (e.g., "includes a \
code example", "mentions the deadline"), write a rule specific enough to \
guarantee that behavior. \
Example: "When the user's question includes a code snippet, always \
include a corrected version in your response."

- If the assertion checks for a GENERAL quality (e.g., "clear and concise", \
"factually accurate"), write a broader rule with a clear boundary.

The assistant is a language model that executes literal instructions. Abstract \
advice like "be empathetic" does NOT reliably produce the right behavior. \
Every rule must describe an observable action (what to say, what to include, \
what to avoid).

STEP 5 — GENERALIZE AND STRUCTURE: Your rules must work for any input in \
this domain, not just the examples shown. Do NOT reference specific test \
inputs. Group related rules under descriptive topic headers \
(e.g., "## Empathy and Acknowledgment", "## Resolution Paths", \
"## Policy and Loyalty"). Do NOT output a flat numbered list — use \
structured sections. Keep the total instruction concise; merge overlapping rules.

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
            min_failed_per_batch=params.get(
                "min_failed_per_batch", max(1, reflection_minibatch_size - 1),
            ),
            min_unseen_per_batch=params.get("min_unseen_per_batch", 0),
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
            "GepaV2Optimizer finished: best_score=%.4f, %d trials recorded",
            result.best_score if hasattr(result, "best_score") else -1,
            len(state.trials),
        )

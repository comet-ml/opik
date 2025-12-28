"""
ARC-AGI task-level HRPO entry point.

Usage overview
--------------
This script wires together the dataset loader, evaluation harness, metric
registry, and Hierarchical Reflective Optimizer (HRPO) for a *single* ARC task:

* Dataset control happens via ``ARC_AGI2_TASK_ID`` (single task) or the standard
  pagination controls (``DATASET_START``/``DATASET_COUNT``).
* Prompts live in ``scripts/arc_agi/prompts`` and are loaded via
  :func:`load_prompts` at import time.
* Scoring is defined in :mod:`scripts.arc_agi.utils.metrics`; the constants
  exported from that module ensure the multi-metric objective and evaluation
  config remain aligned (pass@k, likeness weights, etc.).
"""

from __future__ import annotations

import os
import traceback
from typing import Any

from opik.evaluation.metrics import score_result
from opik_optimizer import ChatPrompt, HierarchicalReflectiveOptimizer
from opik_optimizer.datasets import arc_agi2

try:  # pragma: no cover - satisfied in package context
    from .utils.code_evaluator import EvaluationConfig, evaluate_arc_response
    from .utils.logging_utils import CONSOLE, debug_print
    from .utils.metrics import (
        DEFAULT_METRIC_SEQUENCE,
        DEFAULT_PASS_AT_K,
        LABEL_IOU_REWARD_WEIGHT,
        LIKENESS_REWARD_WEIGHT,
        FOREGROUND_REWARD_WEIGHT,
        normalized_weights,
        build_multi_metric_objective,
    )
    from .utils.prompt_loader import load_prompts
    from .utils.visualization import print_task_preview
except ImportError:  # pragma: no cover - when executed as a script
    import sys
    from pathlib import Path

    SCRIPT_ROOT = Path(__file__).resolve().parents[2]
    if str(SCRIPT_ROOT) not in sys.path:
        sys.path.append(str(SCRIPT_ROOT))

    from scripts.arc_agi.utils.code_evaluator import (  # type: ignore
        EvaluationConfig,
        evaluate_arc_response,
    )
    from scripts.arc_agi.utils.logging_utils import CONSOLE, debug_print  # type: ignore
    from scripts.arc_agi.utils.metrics import (  # type: ignore
        DEFAULT_METRIC_SEQUENCE,
        DEFAULT_PASS_AT_K,
        LABEL_IOU_REWARD_WEIGHT,
        LIKENESS_REWARD_WEIGHT,
        FOREGROUND_REWARD_WEIGHT,
        normalized_weights,
        build_multi_metric_objective,
    )
    from scripts.arc_agi.utils.prompt_loader import load_prompts  # type: ignore
    from scripts.arc_agi.utils.visualization import print_task_preview  # type: ignore

SYSTEM_PROMPT, USER_PROMPT = load_prompts()

DATASET_SPLIT = "train"
DATASET_COUNT = 1
DATASET_START = 0
TEST_MODE = False
ARC_AGI2_TASK_ID = os.getenv("ARC_AGI2_TASK_ID")

EVAL_MODEL = "openai/gpt-5.2"
REASONING_MODEL = "openai/gpt-5.2"
EVAL_TEMPERATURE = 1.0
REASONING_TEMPERATURE = 1.0
HRPO_MAX_TRIALS = 15
HRPO_THREADS = 8
SEED = 42
DEBUG_LOG = True
N_SAMPLES_PER_TRIAL = 6
EVAL_COMPLETIONS_PER_CALL = 4
SANDBOX_TIMEOUT_S = 5.0
RAISE_SCORING_ERRORS = False
COMPOSITE_METRIC_NAME = "arc_agi2_multi"
METRIC_WEIGHTS = dict(
    zip(
        DEFAULT_METRIC_SEQUENCE,
        normalized_weights(DEFAULT_METRIC_SEQUENCE),
    )
)

EVAL_CONTEXT = EvaluationConfig(
    pass_at_k=DEFAULT_PASS_AT_K,
    likeness_weight_train=LIKENESS_REWARD_WEIGHT,
    likeness_weight_test=LIKENESS_REWARD_WEIGHT,
    label_iou_weight=LABEL_IOU_REWARD_WEIGHT,
    foreground_weight_test=FOREGROUND_REWARD_WEIGHT,
    sandbox_timeout_s=SANDBOX_TIMEOUT_S,
    debug_log=DEBUG_LOG,
)


def _handle_scoring_exception(
    metric_name: str, exc: Exception
) -> score_result.ScoreResult:
    """Surface scoring errors without bringing down HRPO."""
    tb = traceback.format_exc()
    message = f"Scoring error: {type(exc).__name__}: {exc}"
    debug_print(message, DEBUG_LOG)
    debug_print(tb, DEBUG_LOG)
    if RAISE_SCORING_ERRORS:
        raise exc
    return score_result.ScoreResult(
        name=metric_name,
        value=0.0,
        scoring_failed=True,
        reason=message,
        metadata={"exception": str(exc), "traceback": tb},
    )


def _evaluation_fn(dataset_item: dict[str, Any], llm_output: str) -> dict[str, Any]:
    """Delegate to the shared ARC evaluator."""
    return evaluate_arc_response(dataset_item, llm_output, EVAL_CONTEXT)


def build_prompt() -> ChatPrompt:
    """Return the baseline prompt used for both baseline eval and HRPO trials."""
    return ChatPrompt(
        name="arc-agi2-hrpo-baseline",
        messages=[
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": USER_PROMPT},
        ],
        model=EVAL_MODEL,
        model_parameters={
            "temperature": EVAL_TEMPERATURE,
            "n": EVAL_COMPLETIONS_PER_CALL,
        },
    )


def _maybe_log_baseline_reason(baseline_eval: Any) -> None:
    """Dump the aggregated baseline reason text to help debugging."""
    if not DEBUG_LOG or not getattr(baseline_eval, "test_results", None):
        return
    first_sr = (
        baseline_eval.test_results[0].score_results[0]
        if baseline_eval.test_results[0].score_results
        else None
    )
    if first_sr:
        debug_print(
            f"Baseline composite metric reason: {first_sr.reason or '<none>'}",
            True,
        )


def _print_run_summary(
    *, context: str, score: float, trials: int | str, llm_calls: int | None
) -> None:
    """Emit a concise Rich line summarizing the completed run."""
    calls_display = llm_calls if llm_calls is not None else "unknown"
    CONSOLE.print(
        f"{context} | score={score:.3f} | trials={trials} | llm_calls={calls_display}"
    )


def main() -> None:
    """Run baseline evaluation followed by HRPO if improvement is needed."""

    composite_metric = build_multi_metric_objective(
        DEFAULT_METRIC_SEQUENCE,
        _evaluation_fn,
        _handle_scoring_exception,
        objective_name=COMPOSITE_METRIC_NAME,
    )

    dataset = arc_agi2(
        split=DATASET_SPLIT,
        count=DATASET_COUNT,
        start=DATASET_START,
        test_mode=TEST_MODE,
        seed=SEED,
        prefer_presets=False,
        filter_by={"task_id": ARC_AGI2_TASK_ID} if ARC_AGI2_TASK_ID else None,
    )

    items = dataset.get_items(1)
    if not items:
        if ARC_AGI2_TASK_ID:
            raise RuntimeError(
                f"Task id '{ARC_AGI2_TASK_ID}' was not found in this dataset slice."
            )
        raise RuntimeError("Dataset returned no items")
    first_item = items[0]

    if DEBUG_LOG:
        debug_print(f"Sample task id: {first_item.get('task_id')}", True)
        print_task_preview(
            first_item.get("training_examples") or [],
            first_item.get("test_inputs") or [],
        )
        if DATASET_COUNT == 1 or len(items) == 1:
            debug_print(
                "Single-task dataset detected; hierarchical analysis will report a single batch (expected).",
                True,
            )

    prompt = build_prompt()

    optimizer = HierarchicalReflectiveOptimizer(
        model=EVAL_MODEL,
        model_parameters={"temperature": EVAL_TEMPERATURE},
        reasoning_model=REASONING_MODEL,
        reasoning_model_parameters={"temperature": REASONING_TEMPERATURE},
        n_threads=HRPO_THREADS,
    )

    baseline_eval = optimizer.evaluate_prompt(
        prompt=prompt,
        dataset=dataset,
        metric=composite_metric,
        n_samples=N_SAMPLES_PER_TRIAL,
        return_evaluation_result=True,
        verbose=1,
    )
    baseline_score = getattr(baseline_eval, "score", None)

    def _composite_from_results() -> float:
        if not getattr(baseline_eval, "test_results", None):
            return 0.0
        per_item_scores: list[float] = []
        for test in baseline_eval.test_results:
            score_map = {
                getattr(sr, "name", ""): getattr(sr, "value", 0.0)
                for sr in getattr(test, "score_results", [])
            }
            if not score_map:
                continue
            composite = sum(
                score_map.get(metric_name, 0.0) * METRIC_WEIGHTS[metric_name]
                for metric_name in DEFAULT_METRIC_SEQUENCE
            )
            per_item_scores.append(composite)
        return sum(per_item_scores) / len(per_item_scores) if per_item_scores else 0.0

    if baseline_score is None:
        baseline_score = _composite_from_results()

    _maybe_log_baseline_reason(baseline_eval)

    if baseline_score >= 0.999:
        _print_run_summary(
            context="ARC-AGI run summary (baseline perfect)",
            score=baseline_score,
            trials=0,
            llm_calls=getattr(optimizer, "llm_call_counter", None),
        )
        return

    result = optimizer.optimize_prompt(
        prompt=prompt,
        dataset=dataset,
        metric=composite_metric,
        n_samples=N_SAMPLES_PER_TRIAL,
        max_trials=HRPO_MAX_TRIALS,
        project_name="ARC-AGI-2 HRPO",
    )

    trials_used = len(result.history) if getattr(result, "history", None) else "unknown"
    llm_calls = result.llm_calls or getattr(optimizer, "llm_call_counter", None)
    _print_run_summary(
        context="ARC-AGI-2 HRPO complete",
        score=result.score,
        trials=trials_used,
        llm_calls=llm_calls,
    )
    prompt_result = result.prompt
    if isinstance(prompt_result, dict):
        prompt_name = next(iter(prompt_result.values())).name
    else:
        prompt_name = prompt_result.name
    CONSOLE.print(f"Best prompt name: {prompt_name}")


if __name__ == "__main__":
    main()

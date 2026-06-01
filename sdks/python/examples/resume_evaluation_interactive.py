"""
Two-phase interactive demo for ``opik.evaluate_resume``.

Run the script twice:

    python examples/resume_evaluation_interactive.py
    # ↓ inspect the experiment in the Opik UI here
    python examples/resume_evaluation_interactive.py

By default both failure modes are exercised at once (task crashes AND
scoring crashes after the task succeeds). To exercise just one, set:

    OPIK_DEMO_FAILURE_MODE=task        # only task-side crashes
    OPIK_DEMO_FAILURE_MODE=scoring     # only post-task scoring crashes
    OPIK_DEMO_FAILURE_MODE=both        # default — both kinds of crashes

Setup
=====

A 20-item sentiment-classification dataset is evaluated with
``trial_count=3``, so each dataset item is invoked 3 times — 60 trials
total per run.

Run 1 — flaky run
=================

Two kinds of failures are sprinkled across trials:

* **Task-side crashes** — the task raises before producing any output.
  Today's resume handled these correctly (trace has no output → trial
  not counted → replay).
* **Scoring-side crashes (new with the marker design)** — the task
  succeeds (output is written to the trace) but a custom metric raises
  a ``BaseException`` mid-scoring. The trace still gets written in the
  outer ``finally`` (it has output), but the happy-path-only marker on
  ``trace.metadata['_opik_evaluation_pending']`` stays at ``True``.
  Resume picks up on that and replays.

After the run, items end up in one of these states:

  * **fully completed** — all 3 trials reached the happy line
  * **partial**         — some trials completed, some failed (either kind)
  * **pending**         — all 3 trials failed

The script catches the run's exception, prints a summary of the mix, and
writes ``~/.opik/demo-resume-state.json`` with the experiment id.

  >>> Open the printed URL in the Opik UI now. Click into one of the
  >>> failed trials and inspect ``metadata`` on the trace — you should
  >>> see ``_opik_evaluation_pending: true``. On a fully-completed
  >>> trial, the same key is ``false``.
  >>> Then re-run the script.

Run 2 — resume with a healthy task and healthy scoring
======================================================

``opik.evaluate_resume`` reads back the resume state, queries the
experiment for completed trials per dataset item, and:

  * leaves **fully-completed items alone** — their 3 stored trials get
    reconstructed into the merged result; the task is not re-invoked.
  * **redoes all 3 trials for partial / pending items** — the
    all-or-nothing rule (see
    ``opik.evaluation.resume.iteration.remaining_runs_for_item``).
    This means the experiment ends up with **more than trial_count rows**
    for those items — old partial rows are kept; new fresh rows are added.
    The merged ``EvaluationResult`` only contains the fresh rows.

The script prints a final summary and marks state as completed.
"""

import json
import os
import time
from pathlib import Path
from typing import Any, Dict, FrozenSet, Optional, Tuple

import opik
from opik import synchronization, url_helpers
from opik.api_objects.experiment import experiment as experiment_module
from opik.evaluation.engine import helpers as engine_helpers
from opik.evaluation.metrics import base_metric, score_result
from opik.evaluation.types import LLMTask


# ---------------------------------------------------------------------------
# Demo configuration
# ---------------------------------------------------------------------------

DATASET_NAME = "resume-interactive-demo-dataset"
EXPERIMENT_NAME = "resume-interactive-demo-experiment"
STATE_FILE = Path.home() / ".opik" / "demo-resume-state.json"

TRIAL_COUNT = 3

# 20 review/sentiment pairs. The review string is the dataset item's
# identity for task-side logic (each is unique).
REVIEWS = [
    ("I love this product!", "positive"),
    ("Worst experience ever.", "negative"),
    ("It was okay, nothing special.", "neutral"),
    ("Absolutely fantastic, highly recommend!", "positive"),
    ("Total waste of money.", "negative"),
    ("Mediocre at best.", "neutral"),
    ("Amazing quality and great service!", "positive"),
    ("I want a refund.", "negative"),
    ("Pretty good but room for improvement.", "neutral"),
    ("Five stars, no complaints.", "positive"),
    ("Did exactly what I needed.", "positive"),
    ("Stopped working after a week.", "negative"),
    ("Average — nothing memorable.", "neutral"),
    ("Best purchase of the year.", "positive"),
    ("Terrible build quality.", "negative"),
    ("Could be better, could be worse.", "neutral"),
    ("Highly recommend to anyone.", "positive"),
    ("Returned it the next day.", "negative"),
    ("Acceptable but unremarkable.", "neutral"),
    ("Loved every minute of using it.", "positive"),
]

# Deterministic failure pattern for run 1. Pairs are
# ``(review_index, trial_index)`` — trial indices are 0-based, in call
# order per item.
#
# Task-side failures: the task raises before producing any output.
# Trace ends up with ``output=None``; resume already handled these
# correctly before the marker design.
TASK_FLAKY_TRIALS: FrozenSet[Tuple[int, int]] = frozenset(
    {
        (4, 0),
        (4, 1),
        (4, 2),
        (11, 0),
        (11, 1),
        (2, 1),
    }
)

# Scoring-side failures: the task succeeds and writes its output to
# the trace, then a custom metric raises a ``BaseException`` (mid-
# scoring). The outer ``finally`` still writes the trace (it has
# output), but the happy-path marker stays at ``pending=True``. This
# is the case the marker design exists to detect — without it, resume
# would see ``output != None`` and incorrectly skip the trial.
SCORING_FLAKY_TRIALS: FrozenSet[Tuple[int, int]] = frozenset(
    {
        (7, 0),
        (14, 2),
        (18, 0),
    }
)


# Which failure modes to exercise. Override via env var, e.g.
# ``OPIK_DEMO_FAILURE_MODE=scoring`` to demo only the new bug case.
_FAILURE_MODE: str = (
    os.environ.get("OPIK_DEMO_FAILURE_MODE", "both").strip().lower()
)
if _FAILURE_MODE not in {"task", "scoring", "both"}:
    raise SystemExit(
        f"Invalid OPIK_DEMO_FAILURE_MODE={_FAILURE_MODE!r}. "
        "Expected one of: task, scoring, both."
    )

ACTIVE_TASK_FLAKY: FrozenSet[Tuple[int, int]] = (
    TASK_FLAKY_TRIALS if _FAILURE_MODE in {"task", "both"} else frozenset()
)
ACTIVE_SCORING_FLAKY: FrozenSet[Tuple[int, int]] = (
    SCORING_FLAKY_TRIALS
    if _FAILURE_MODE in {"scoring", "both"}
    else frozenset()
)


# ---------------------------------------------------------------------------
# Task / dataset helpers
# ---------------------------------------------------------------------------


def _classify_review(review_text: str) -> str:
    """Pretend to call an LLM. Deterministic lookup against ``REVIEWS``."""
    time.sleep(0.05)
    for text, sentiment in REVIEWS:
        if text == review_text:
            return sentiment
    raise ValueError(f"Unknown review: {review_text!r}")


def _review_index(review_text: str) -> int:
    for index, (text, _) in enumerate(REVIEWS):
        if text == review_text:
            return index
    raise ValueError(f"Unknown review: {review_text!r}")


def _make_flaky_task() -> LLMTask:
    """
    Build the sporadic-failure task. Uses a closure to count per-item
    call indices — deterministic with ``task_threads=1`` (the engine
    submits trials for a given item in order).
    """
    call_count_by_review: Dict[str, int] = {}

    def flaky_task(item: Dict[str, Any]) -> Dict[str, str]:
        review = item["input"]["review"]
        trial_index = call_count_by_review.get(review, 0)
        call_count_by_review[review] = trial_index + 1

        if (_review_index(review), trial_index) in ACTIVE_TASK_FLAKY:
            raise RuntimeError(
                f"Simulated task failure: review #{_review_index(review)} "
                f"trial #{trial_index}"
            )
        return {"output": _classify_review(review)}

    return flaky_task


def healthy_task(item: Dict[str, Any]) -> Dict[str, str]:
    """Resume-run task: no crashes, always returns the right answer."""
    return {"output": _classify_review(item["input"]["review"])}


def _make_flaky_metric() -> base_metric.BaseMetric:
    """
    A metric whose ``score()`` raises ``BaseException`` for specific
    (item, trial) pairs — driven by ``ACTIVE_SCORING_FLAKY``.

    The metric_evaluator inside the engine wraps each metric call in
    ``except Exception`` — that catches ``RuntimeError`` and friends
    and converts them to ``ScoreResult(scoring_failed=True)``. We use
    ``BaseException`` (``SystemExit``) here so the exception
    **escapes** that handler, just like a KeyboardInterrupt would.
    The escaped exception propagates through
    ``_compute_test_result_for_test_case`` and out of the ``with
    helpers.evaluate_llm_task_context(...)`` block before the
    happy-path marker-clear line is reached. Result: the trace is
    written (it has output), the experiment item is inserted, but
    the pending marker stays at ``True``.

    Per-review trial counter mirrors the flaky task — deterministic
    with ``task_threads=1``.
    """
    call_count_by_review: Dict[str, int] = {}

    class FlakyAccuracyMetric(base_metric.BaseMetric):
        def __init__(self) -> None:
            super().__init__(name="accuracy")

        def score(
            self,
            output: str,
            reference: str,
            review: str,
            **_unused: Any,
        ) -> score_result.ScoreResult:
            trial_index = call_count_by_review.get(review, 0)
            call_count_by_review[review] = trial_index + 1

            if (_review_index(review), trial_index) in ACTIVE_SCORING_FLAKY:
                raise SystemExit(
                    f"Simulated scoring failure (bypasses except Exception): "
                    f"review #{_review_index(review)} trial #{trial_index}"
                )

            return score_result.ScoreResult(
                name=self.name,
                value=1.0 if output == reference else 0.0,
            )

    return FlakyAccuracyMetric()


def _make_healthy_accuracy_metric() -> base_metric.BaseMetric:
    """Phase-2 metric — same name as the flaky one, never raises."""

    class HealthyAccuracyMetric(base_metric.BaseMetric):
        def __init__(self) -> None:
            super().__init__(name="accuracy")

        def score(
            self,
            output: str,
            reference: str,
            **_unused: Any,
        ) -> score_result.ScoreResult:
            return score_result.ScoreResult(
                name=self.name,
                value=1.0 if output == reference else 0.0,
            )

    return HealthyAccuracyMetric()


# ---------------------------------------------------------------------------
# Per-item state summaries
# ---------------------------------------------------------------------------


def _completed_trials_by_dataset_item_id(
    experiment: experiment_module.Experiment,
) -> Dict[str, int]:
    """
    Count fully-completed trials per dataset item using the same predicate
    ``opik.evaluate_resume`` uses — the trace-level marker, not output
    presence. Items whose task succeeded but whose scoring crashed
    (output set, marker still pending) are correctly counted as
    incomplete here.
    """
    pending_key = engine_helpers.EVALUATION_PENDING_METADATA_KEY
    counts: Dict[str, int] = {}
    for item in experiment.get_items():
        trace_metadata = item.trace_metadata or {}
        if trace_metadata.get(pending_key) is not False:
            continue
        counts[item.dataset_item_id] = counts.get(item.dataset_item_id, 0) + 1
    return counts


def _total_experiment_items_by_dataset_item_id(experiment) -> Dict[str, int]:
    counts: Dict[str, int] = {}
    for item in experiment.get_items():
        counts[item.dataset_item_id] = counts.get(item.dataset_item_id, 0) + 1
    return counts


def _summarize_states(
    completed_per_id: Dict[str, int], expected_per_item: int, total_items: int
) -> Tuple[int, int, int]:
    fully = sum(
        1 for c in completed_per_id.values() if c >= expected_per_item
    )
    partial = sum(
        1
        for c in completed_per_id.values()
        if 0 < c < expected_per_item
    )
    pending = total_items - fully - partial
    return fully, partial, pending


def _print_state_table(experiment, label: str) -> None:
    """Render a small table of per-item state for the demo."""
    completed_per_id = _completed_trials_by_dataset_item_id(experiment)
    total_rows_per_id = _total_experiment_items_by_dataset_item_id(experiment)
    fully, partial, pending = _summarize_states(
        completed_per_id, TRIAL_COUNT, len(REVIEWS)
    )

    print(f"--- {label} ---")
    print(
        f"{'state':<20}{'items':>8}{'expected/item':>16}"
        f"{'avg rows/item':>18}"
    )
    print("-" * 62)
    avg_rows = (
        sum(total_rows_per_id.values()) / len(total_rows_per_id)
        if total_rows_per_id
        else 0.0
    )
    print(
        f"{'fully completed':<20}{fully:>8d}{TRIAL_COUNT:>16d}"
        f"{'':>18}"
    )
    print(f"{'partial':<20}{partial:>8d}{TRIAL_COUNT:>16d}{'':>18}")
    print(f"{'pending':<20}{pending:>8d}{TRIAL_COUNT:>16d}{'':>18}")
    print("-" * 62)
    print(
        f"{'TOTAL':<20}{len(REVIEWS):>8d}{TRIAL_COUNT:>16d}"
        f"{avg_rows:>18.2f}"
    )
    print()


# ---------------------------------------------------------------------------
# State file helpers
# ---------------------------------------------------------------------------


def _load_state() -> Optional[Dict[str, Any]]:
    if not STATE_FILE.exists():
        return None
    try:
        return json.loads(STATE_FILE.read_text())
    except json.JSONDecodeError:
        return None


def _save_state(state: Dict[str, Any]) -> None:
    STATE_FILE.parent.mkdir(parents=True, exist_ok=True)
    STATE_FILE.write_text(json.dumps(state, indent=2))


# ---------------------------------------------------------------------------
# Phases
# ---------------------------------------------------------------------------


def _phase_one_evaluate(opik_client: opik.Opik) -> None:
    print("=" * 64)
    print(
        f"PHASE 1 — evaluate {len(REVIEWS)} items × trial_count={TRIAL_COUNT}"
    )
    print("=" * 64)
    print(f"Failure mode (OPIK_DEMO_FAILURE_MODE): {_FAILURE_MODE}")
    print(
        f"  task-side failures planned   : {len(ACTIVE_TASK_FLAKY):>2d} of "
        f"{len(REVIEWS) * TRIAL_COUNT} trials"
    )
    print(
        f"  scoring-side failures planned: {len(ACTIVE_SCORING_FLAKY):>2d} of "
        f"{len(REVIEWS) * TRIAL_COUNT} trials"
    )
    print()

    try:
        opik_client.delete_dataset(DATASET_NAME)
    except Exception:
        pass

    dataset = opik_client.create_dataset(DATASET_NAME)
    dataset.insert(
        [
            {
                "input": {"review": text},
                "expected_sentiment": expected,
                # Top-level copy so the flaky metric can read it via a
                # simple ``scoring_key_mapping`` entry — no nested
                # field-path lookup needed.
                "review": text,
            }
            for text, expected in REVIEWS
        ]
    )
    print(f"Created dataset '{DATASET_NAME}' with {len(REVIEWS)} items.")

    try:
        opik.evaluate(
            dataset=dataset,
            task=_make_flaky_task(),
            scoring_metrics=[_make_flaky_metric()],
            # ``review`` is sourced from the nested dataset key so the
            # flaky metric can identify the item it's scoring. ``reference``
            # stays mapped to the expected sentiment.
            scoring_key_mapping={
                "reference": "expected_sentiment",
                "review": "review",
            },
            experiment_name=EXPERIMENT_NAME,
            trial_count=TRIAL_COUNT,
            task_threads=1,  # makes the per-item trial order deterministic
            verbose=0,
        )
    except (RuntimeError, SystemExit) as exc:
        print(f"Evaluation interrupted (as designed): {exc}")

    # The engine re-raised before flushing its streamer, so the backend may
    # still be ingesting the experiment items / traces we just produced.
    # Flush explicitly + wait for the BE to settle so the state table below
    # reflects the final partial state.
    opik_client.flush()

    # Recover the experiment id by name; evaluate() raised, so we don't
    # have the EvaluationResult. The experiment record was created before
    # task execution, so it exists. Pick the one with our current dataset
    # — past runs of the demo can leave orphaned experiments with the same
    # name pointing at deleted datasets.
    experiments = opik_client.get_experiments_by_name(EXPERIMENT_NAME)
    assert experiments, "Expected the demo experiment to have been created."
    matching = [e for e in experiments if e.dataset_name == DATASET_NAME]
    assert matching, (
        f"No experiment named {EXPERIMENT_NAME!r} found that points at the "
        f"current dataset {DATASET_NAME!r}."
    )
    # Get the freshest one via the by-id endpoint so we have the full payload.
    experiment = opik_client.get_experiment_by_id(matching[-1].id)
    experiment_id = experiment.id

    expected_rows = len(REVIEWS) * TRIAL_COUNT
    synchronization.until(
        lambda: len(experiment.get_items()) == expected_rows,
        max_try_seconds=15,
    )

    experiment_url = url_helpers.get_experiment_url_by_id(
        experiment_id=experiment_id,
        dataset_id=dataset.id,
        url_override=opik_client.config.url_override,
    )

    _save_state(
        {
            "phase": "ran_with_partial_completion",
            "experiment_id": experiment_id,
            "experiment_url": experiment_url,
            "total_items": len(REVIEWS),
            "trial_count": TRIAL_COUNT,
        }
    )

    print()
    print(f"Experiment id : {experiment_id}")
    print(f"Experiment URL: {experiment_url}")
    print()
    _print_state_table(experiment, "After phase 1 (partial)")

    print(">>> Open the URL above to inspect the partial state.")
    print(">>> Then re-run this script to resume.")


def _phase_two_resume(opik_client: opik.Opik, state: Dict[str, Any]) -> None:
    print("=" * 64)
    print("PHASE 2 — resume the experiment with a healthy task")
    print("=" * 64)

    experiment_id = state["experiment_id"]
    experiment_url = state["experiment_url"]

    experiment_before = opik_client.get_experiment_by_id(experiment_id)
    print(f"Experiment id : {experiment_id}")
    print(f"Experiment URL: {experiment_url}")
    print()
    _print_state_table(experiment_before, "Before resume")

    print("Calling opik.evaluate_resume() ...")
    result = opik.evaluate_resume(
        experiment_id=experiment_id,
        task=healthy_task,
        scoring_metrics=[_make_healthy_accuracy_metric()],
        scoring_key_mapping={
            "reference": "expected_sentiment",
            "review": "review",
        },
        verbose=0,
    )

    experiment_after = opik_client.get_experiment_by_id(experiment_id)
    print()
    _print_state_table(experiment_after, "After resume")

    print(
        f"Merged EvaluationResult: {len(result.test_results)} test results"
    )
    print(
        f"  (expected: {len(REVIEWS)} items × {TRIAL_COUNT} trials = "
        f"{len(REVIEWS) * TRIAL_COUNT})"
    )
    print()
    print(
        "Note: the experiment's raw row count may exceed "
        f"{len(REVIEWS) * TRIAL_COUNT} for items that were partial — "
        "their original trials are kept as historical event records, and "
        "fresh trials are added on resume. The merged result intentionally "
        "reflects only the fresh trials for those items (plus reconstructed "
        "trials for items that were already fully completed)."
    )
    print()
    print(f"Experiment URL: {experiment_url}")
    print(">>> Open the URL above to see the experiment fully populated.")

    _save_state(
        {
            "phase": "completed",
            "experiment_id": experiment_id,
            "experiment_url": experiment_url,
            "total_items": state["total_items"],
            "trial_count": state.get("trial_count", TRIAL_COUNT),
        }
    )


def _print_already_done(state: Dict[str, Any]) -> None:
    print("=" * 64)
    print("Already completed.")
    print("=" * 64)
    print(f"Experiment id : {state['experiment_id']}")
    print(f"Experiment URL: {state['experiment_url']}")
    print()
    print(f"To start over: rm {STATE_FILE}")


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------


def main() -> None:
    opik_client = opik.Opik()
    state = _load_state()

    if state is None:
        _phase_one_evaluate(opik_client)
    elif state.get("phase") == "ran_with_partial_completion":
        _phase_two_resume(opik_client, state)
    else:
        _print_already_done(state)


if __name__ == "__main__":
    main()

"""GEPA adapter bridging GEPA's evaluation interface to the framework's EvaluationAdapter.

The gepa library (v0.1.0) interacts with this adapter through:
  - adapter.evaluate(batch, candidate, capture_traces)
  - adapter.make_reflective_dataset(candidate, eval_batch, components)
  - adapter.propose_new_texts (optional, set to None)

Lifecycle events are received via the GEPAProgressCallback, which fires
on_evaluation_start RIGHT BEFORE adapter.evaluate(). The adapter uses
this metadata for accurate phase/parent tracking. For the seed eval
(which happens before callbacks are active), the adapter falls back to
detecting the initialization phase.
"""

from __future__ import annotations

import logging
from collections.abc import Mapping, Sequence
from typing import Any, TYPE_CHECKING

from .candidate_tracker import CandidateTracker, _candidate_key
from .reflective_dataset_builder import ReflectiveDatasetBuilder
from .reflection_proposer import ReflectionProposer

logger = logging.getLogger(__name__)

if TYPE_CHECKING:
    from gepa.core.adapter import EvaluationBatch
    from opik.evaluation.metrics.score_result import ScoreResult
    from opik_optimizer_framework.evaluation_adapter import EvaluationAdapter
    from opik_optimizer_framework.types import TrialResult
    from .failure_aware_sampler import FailureAwareBatchSampler


DatasetItem = dict[str, Any]

AssertionDetail = dict[str, Any]  # {"name": str, "value": float, "reason": str}
RunFeedback = dict[str, Any]  # {"output": str, "score": float, "assertions": list[AssertionDetail]}
ItemFeedback = dict[str, Any]  # {"runs": list[RunFeedback], "score": float}


def _item_score(passed: bool, assertion_values: list[float], num_items: int) -> float:
    """Compute a per-item score in [0, 1] aligned with pass_rate.

    Uses the item's pass/fail status from ``build_suite_result`` (which
    respects execution_policy and pass_threshold) as the source of truth.

    Returns 1.0 for passing items, or ``ε × assertion_frac`` for failing
    items, where ``ε = 1 / (num_items + 1)``.

    The gap between a passing item (1.0) and any failing item (< ε < 1)
    ensures GEPA's ``mean(per_item_scores)`` ranks candidates the same way
    as the framework's pass_rate. The assertion fraction within failing
    items provides gradient for GEPA's subsample acceptance gate.

    Previous approach (mean of assertion values) caused GEPA to prefer
    "uniformly decent" candidates over ones with higher pass_rate.
    """
    if passed:
        return 1.0
    if not assertion_values:
        return 0.0
    epsilon = 1.0 / (num_items + 1) if num_items > 0 else 0.0
    assertion_frac = sum(1 for v in assertion_values if v == 1.0) / len(assertion_values)
    return epsilon * assertion_frac


def _extract_per_item_feedback(raw_result: Any) -> dict[str, ItemFeedback]:
    """Extract per-item feedback from the raw EvaluationResult.

    Uses ``build_suite_result`` to determine item pass/fail (respecting
    execution_policy and pass_threshold), then computes GEPA-aligned scores.

    When multiple runs exist for the same item (runs_per_item > 1), all runs
    are preserved so the reflection LLM can see what varies across attempts.

    Returns a dict keyed by dataset_item_id with:
      - runs: list of dicts, each with output, score, assertions
      - score: item-level score for GEPA's numeric optimization
    """
    feedback: dict[str, ItemFeedback] = {}
    if raw_result is None or not hasattr(raw_result, "test_results"):
        return feedback

    from opik.api_objects.dataset.evaluation_suite.suite_result_constructor import (
        build_suite_result,
    )

    try:
        suite_result = build_suite_result(raw_result)
    except Exception:
        logger.warning("Failed to build suite result for feedback", exc_info=True)
        return feedback

    num_items: int = len(suite_result.item_results)

    # First pass: collect runs per item (for reflection dataset)
    for test_result in raw_result.test_results:
        item_id: str = str(test_result.test_case.dataset_item_id)
        task_output: dict[str, Any] = getattr(test_result.test_case, "task_output", {}) or {}
        output_text: str = str(task_output.get("output", ""))

        assertions: list[AssertionDetail] = []
        sr: ScoreResult
        for sr in test_result.score_results:
            assertions.append({
                "name": sr.name,
                "value": float(sr.value),
                "reason": sr.reason or "",
            })

        run: RunFeedback = {"output": output_text, "score": 0.0, "assertions": assertions}

        if item_id not in feedback:
            feedback[item_id] = {"runs": [run], "score": 0.0}
        else:
            feedback[item_id]["runs"].append(run)

    # Second pass: compute item scores using suite_result as source of truth
    for item_id, item_feedback in feedback.items():
        item_result = suite_result.item_results.get(item_id)
        passed: bool = item_result.passed if item_result is not None else False

        all_assertion_values: list[float] = [
            a["value"] for run in item_feedback["runs"] for a in run["assertions"]
        ]

        item_feedback["score"] = _item_score(passed, all_assertion_values, num_items)

        for run in item_feedback["runs"]:
            run["score"] = item_feedback["score"]

    return feedback


class GEPAProgressCallback:
    """Bridges GEPA's lifecycle events to the adapter's state management.

    GEPA dispatches events via ``notify_callbacks(callbacks, method_name, event)``.
    Key events:

    - ``on_iteration_start``: resets per-iteration state, records step number
    - ``on_candidate_selected``: records which candidate was selected as parent
    - ``on_evaluation_start``: fires RIGHT BEFORE ``adapter.evaluate()`` — stores
      pending metadata (parent_ids, capture_traces, candidate_idx)
    - ``on_valset_evaluated``: maps GEPA candidate index → framework candidate_id
    - ``on_merge_accepted``: records merge parent IDs
    """

    def __init__(self, adapter: FrameworkGEPAAdapter) -> None:
        self._adapter = adapter

    def on_iteration_start(self, event: dict[str, Any]) -> None:
        self._adapter._on_new_step(event["iteration"])

    def on_candidate_selected(self, event: dict[str, Any]) -> None:
        self._adapter._on_candidate_selected(event["candidate_idx"])

    def on_evaluation_start(self, event: dict[str, Any]) -> None:
        self._adapter._on_evaluation_start(
            candidate_idx=event["candidate_idx"],
            parent_ids=event["parent_ids"],
            capture_traces=event["capture_traces"],
        )

    def on_valset_evaluated(self, event: dict[str, Any]) -> None:
        self._adapter._on_valset_evaluated(
            event["candidate_idx"], event["candidate"],
        )

    def on_merge_accepted(self, event: dict[str, Any]) -> None:
        self._adapter._on_merge_accepted(event["parent_ids"])


class FrameworkGEPAAdapter:
    """Thin facade that bridges GEPA's evaluate/reflect interface to the framework.

    Delegates to three collaborators:
    - CandidateTracker: candidate identity, parent lineage, GEPA index mapping
    - ReflectiveDatasetBuilder: feedback dataset construction for reflection
    - ReflectionProposer: reflection LLM interaction and logging
    """

    def __init__(
        self,
        config_builder: Any,
        evaluation_adapter: EvaluationAdapter,
        candidate_tracker: CandidateTracker | None = None,
        dataset_builder: ReflectiveDatasetBuilder | None = None,
        reflection_proposer: ReflectionProposer | None = None,
        batch_sampler: FailureAwareBatchSampler | None = None,
        reflection_lm: Any = None,
        reflection_prompt_template: str | None = None,
        config_descriptions: dict[str, str] | None = None,
        persistent_failure_threshold: int = 7,
    ) -> None:
        self._config_builder = config_builder
        self._evaluation_adapter = evaluation_adapter
        self._batch_sampler = batch_sampler

        self._tracker = candidate_tracker or CandidateTracker()
        self._dataset_builder = dataset_builder or ReflectiveDatasetBuilder(
            batch_sampler, persistent_failure_threshold=persistent_failure_threshold,
        )
        if reflection_proposer is not None:
            self._proposer: ReflectionProposer | None = reflection_proposer
        elif reflection_lm is not None:
            self._proposer = ReflectionProposer(
                reflection_lm, reflection_prompt_template, config_descriptions,
            )
        else:
            self._proposer = None

        self._standalone_reflection_log: list[dict[str, Any]] = []
        self._last_per_item_feedback: dict[str, dict[str, Any]] = {}
        self._full_dataset_size: int | None = None
        self.best_full_eval_trial_score: float = 0.0
        self._full_eval_score_history: list[float] = []

        self._cached_full_eval_scores: dict[str, dict[str, float]] = {}
        self._cache_max_entries: int = 10

    # -- Delegation to CandidateTracker ----------------------------------------

    def register_baseline(
        self,
        seed_candidate: dict[str, str],
        baseline_candidate_id: str,
    ) -> None:
        self._tracker.register_baseline(seed_candidate, baseline_candidate_id)

    def _on_new_step(self, iteration: int) -> None:
        self._tracker.on_new_step(iteration)

    def _on_candidate_selected(self, candidate_idx: int) -> None:
        self._tracker.on_candidate_selected(candidate_idx)

    def _on_evaluation_start(
        self,
        candidate_idx: int | None,
        parent_ids: Sequence[int],
        capture_traces: bool,
    ) -> None:
        self._tracker.on_evaluation_start(candidate_idx, parent_ids, capture_traces)

    def _on_valset_evaluated(
        self, candidate_idx: int, candidate: dict[str, str],
    ) -> None:
        self._tracker.on_valset_evaluated(candidate_idx, candidate)

    def _on_merge_accepted(self, parent_ids: Sequence[int]) -> None:
        self._tracker.on_merge_accepted(parent_ids)

    # -- Public properties (facade over CandidateTracker) ----------------------

    @property
    def known_candidates(self) -> dict[str, str]:
        return self._tracker._known_candidates

    @property
    def candidate_parents(self) -> dict[str, list[str]]:
        return self._tracker._candidate_parents

    @property
    def baseline_candidate_id(self) -> str | None:
        return self._tracker._baseline_candidate_id

    @property
    def seed_candidate_key(self) -> str | None:
        return self._tracker._seed_candidate_key

    @property
    def gepa_idx_to_candidate_id(self) -> dict[int, str]:
        return self._tracker._gepa_idx_to_candidate_id

    @gepa_idx_to_candidate_id.setter
    def gepa_idx_to_candidate_id(self, value: dict[int, str]) -> None:
        self._tracker._gepa_idx_to_candidate_id = value

    @property
    def current_step(self) -> int:
        return self._tracker._current_step

    @current_step.setter
    def current_step(self, value: int) -> None:
        self._tracker._current_step = value

    @property
    def selected_parent_id(self) -> str | None:
        return self._tracker._selected_parent_id

    @selected_parent_id.setter
    def selected_parent_id(self, value: str | None) -> None:
        self._tracker._selected_parent_id = value

    @property
    def pending_eval_parent_ids(self) -> list[int] | None:
        return self._tracker._pending_eval_parent_ids

    @pending_eval_parent_ids.setter
    def pending_eval_parent_ids(self, value: list[int] | None) -> None:
        self._tracker._pending_eval_parent_ids = value

    @property
    def pending_eval_capture_traces(self) -> bool | None:
        return self._tracker._pending_eval_capture_traces

    @pending_eval_capture_traces.setter
    def pending_eval_capture_traces(self, value: bool | None) -> None:
        self._tracker._pending_eval_capture_traces = value

    @property
    def pending_eval_candidate_idx(self) -> int | None:
        return self._tracker._pending_eval_candidate_idx

    @pending_eval_candidate_idx.setter
    def pending_eval_candidate_idx(self, value: int | None) -> None:
        self._tracker._pending_eval_candidate_idx = value

    @property
    def pending_merge_parent_ids(self) -> list[int] | None:
        return self._tracker._pending_merge_parent_ids

    @pending_merge_parent_ids.setter
    def pending_merge_parent_ids(self, value: list[int] | None) -> None:
        self._tracker._pending_merge_parent_ids = value

    @property
    def reflection_log(self) -> list[dict[str, Any]]:
        if self._proposer:
            return self._proposer.reflection_log
        return self._standalone_reflection_log

    @property
    def reflection_prompt_template(self) -> str | None:
        return self._proposer.reflection_prompt_template if self._proposer else None

    # -- Core evaluate ---------------------------------------------------------

    def _build_evaluation_batch(
        self,
        batch: list[DatasetItem],
        per_item: dict[str, dict[str, Any]],
        trial: TrialResult | None,
        capture_traces: bool,
    ) -> EvaluationBatch:
        """Convert per-item feedback into GEPA's EvaluationBatch format."""
        from gepa.core.adapter import EvaluationBatch

        outputs: list[dict[str, Any]] = []
        scores: list[float] = []
        trajectories: list[dict[str, Any]] | None = [] if capture_traces else None

        for inst in batch:
            item_id = str(inst.get("id", ""))
            item_feedback = per_item.get(item_id, {})
            runs = item_feedback.get("runs", [])
            score = item_feedback.get("score", 0.0)
            output_text = runs[0]["output"] if runs else ""

            outputs.append({"output": output_text})
            scores.append(score)

            if trajectories is not None:
                trajectories.append(
                    {
                        "input": inst,
                        "runs": runs,
                        "score": score,
                    }
                )

        if not per_item and trial is not None:
            trial_score = trial.score
            for i in range(len(batch)):
                scores[i] = trial_score

        return EvaluationBatch(
            outputs=outputs,
            scores=scores,
            trajectories=trajectories,
        )

    def evaluate(
        self,
        batch: list[DatasetItem],
        candidate: dict[str, str],
        capture_traces: bool = False,
    ) -> EvaluationBatch:
        """Evaluate a GEPA candidate against a batch of data instances."""
        key = _candidate_key(candidate)

        pending = self._tracker.get_pending_capture_traces()
        effective_capture_traces = pending if pending is not None else capture_traces

        existing_candidate_id = self._tracker.get_existing_candidate_id(key)
        parent_candidate_ids = self._tracker.resolve_parent_ids(key)

        dataset_item_ids = [
            str(inst.get("id"))
            for inst in batch
            if inst.get("id") is not None
        ]

        config = self._config_builder(candidate)

        batch_index = self.current_step if self.current_step >= 0 else None

        if self._full_dataset_size is None:
            self._full_dataset_size = len(batch)

        is_full_eval = len(batch) >= self._full_dataset_size
        is_known = key in self._tracker._known_candidates
        if is_full_eval:
            experiment_type = None
        elif not is_known:
            experiment_type = "mutation"
        else:
            experiment_type = "mini-batch"

        logger.debug(
            "[adapter.evaluate] batch_index=%s num_items=%d "
            "capture_traces=%s candidate_id=%s parent_ids=%s is_full=%s "
            "experiment_type=%s",
            batch_index,
            len(batch),
            effective_capture_traces,
            existing_candidate_id,
            parent_candidate_ids,
            is_full_eval,
            experiment_type,
        )

        trial, raw_result = self._evaluation_adapter.evaluate_with_details(
            config=config,
            dataset_item_ids=dataset_item_ids,
            parent_candidate_ids=parent_candidate_ids,
            candidate_id=existing_candidate_id,
            batch_index=batch_index,
            num_items=len(batch),
            capture_traces=effective_capture_traces,
            experiment_type=experiment_type,
        )

        self._tracker.clear_pending_eval()

        if trial is not None:
            self._tracker.record_trial(key, trial, parent_candidate_ids, effective_capture_traces)
            if is_full_eval:
                self._full_eval_score_history.append(trial.score)
                if trial.score > self.best_full_eval_trial_score:
                    self.best_full_eval_trial_score = trial.score

        per_item = _extract_per_item_feedback(raw_result)
        self._last_per_item_feedback = per_item

        if self._batch_sampler is not None and is_full_eval:
            self._batch_sampler.update_scores(per_item)

        if self._batch_sampler is not None and per_item:
            self._batch_sampler.update_assertion_failures(per_item)

        if is_full_eval and per_item:
            self._cached_full_eval_scores[key] = {
                item_id: data["score"] for item_id, data in per_item.items()
            }
            if len(self._cached_full_eval_scores) > self._cache_max_entries:
                oldest_key = next(iter(self._cached_full_eval_scores))
                del self._cached_full_eval_scores[oldest_key]

        use_cached_scores = (
            not is_full_eval
            and is_known
            and key in self._cached_full_eval_scores
        )
        if use_cached_scores:
            cached = self._cached_full_eval_scores[key]
            for inst in batch:
                item_id = str(inst.get("id", ""))
                if item_id in cached:
                    per_item.setdefault(item_id, {})["score"] = cached[item_id]

        return self._build_evaluation_batch(
            batch, per_item, trial, effective_capture_traces,
        )

    # -- Delegation to ReflectiveDatasetBuilder --------------------------------

    def make_reflective_dataset(
        self,
        candidate: dict[str, str],
        eval_batch: EvaluationBatch,
        components_to_update: list[str],
    ) -> dict[str, list[dict[str, Any]]]:
        return self._dataset_builder.build(candidate, eval_batch, components_to_update)

    # -- Delegation to ReflectionProposer --------------------------------------

    def propose_new_texts(
        self,
        candidate: dict[str, str],
        reflective_dataset: Mapping[str, Sequence[Mapping[str, Any]]],
        components_to_update: list[str],
    ) -> dict[str, str]:
        if self._proposer is None:
            raise ValueError(
                "reflection_lm is required for propose_new_texts. "
                "Pass it via FrameworkGEPAAdapter constructor."
            )
        return self._proposer.propose(candidate, reflective_dataset, components_to_update)

    # -- Problematic items summary ---------------------------------------------

    def get_problematic_items_summary(self) -> list[dict[str, Any]]:
        """Return items that failed consistently, sorted by failure streak."""
        if self._batch_sampler is None:
            return []

        stuck = self._batch_sampler.get_stuck_items(min_streak=2)
        if not stuck:
            return []

        summary = []
        for item_id, streak in sorted(stuck.items(), key=lambda x: -x[1]):
            assertions = self._batch_sampler.get_failed_assertions(item_id)
            summary.append({
                "item_id": item_id,
                "failure_streak": streak,
                "failing_assertions": assertions,
            })
        return summary

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
    from opik_optimizer_framework.evaluation_adapter import EvaluationAdapter
    from opik_optimizer_framework.types import TrialResult
    from .failure_aware_sampler import FailureAwareBatchSampler


DatasetItem = dict[str, Any]

SYSTEM_PROMPT_KEY = "system_prompt"


def _extract_per_item_feedback(raw_result: Any) -> dict[str, dict[str, Any]]:
    """Extract per-item feedback from the raw EvaluationResult.

    When multiple runs exist for the same item (runs_per_item > 1), all runs
    are preserved so the reflection LLM can see what varies across attempts.

    Returns a dict keyed by dataset_item_id with:
      - runs: list of dicts, each with output, score, assertions
      - score: mean score across all runs (for GEPA's numeric optimization)
    """
    feedback: dict[str, dict[str, Any]] = {}
    if raw_result is None or not hasattr(raw_result, "test_results"):
        return feedback

    for test_result in raw_result.test_results:
        item_id = str(test_result.test_case.dataset_item_id)
        task_output = getattr(test_result.test_case, "task_output", {}) or {}
        output_text = str(task_output.get("output", ""))

        assertions = []
        scores = []
        for sr in test_result.score_results:
            value = float(getattr(sr, "value", 0.0))
            scores.append(value)
            assertions.append({
                "name": getattr(sr, "name", ""),
                "value": value,
                "reason": getattr(sr, "reason", None) or "",
            })

        run_score = sum(scores) / len(scores) if scores else 0.0
        run = {"output": output_text, "score": run_score, "assertions": assertions}

        if item_id not in feedback:
            feedback[item_id] = {"runs": [run], "score": run_score}
        else:
            feedback[item_id]["runs"].append(run)
            all_scores = [r["score"] for r in feedback[item_id]["runs"]]
            feedback[item_id]["score"] = sum(all_scores) / len(all_scores)

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
        prompt_descriptions: dict[str, str] | None = None,
    ) -> None:
        self._config_builder = config_builder
        self._evaluation_adapter = evaluation_adapter
        self._batch_sampler = batch_sampler

        self._tracker = candidate_tracker or CandidateTracker()
        self._dataset_builder = dataset_builder or ReflectiveDatasetBuilder(batch_sampler)
        if reflection_proposer is not None:
            self._proposer: ReflectionProposer | None = reflection_proposer
        elif reflection_lm is not None:
            self._proposer = ReflectionProposer(
                reflection_lm, reflection_prompt_template, prompt_descriptions,
            )
        else:
            self._proposer = None

        self._last_per_item_feedback: dict[str, dict[str, Any]] = {}
        self._full_dataset_size: int | None = None
        self.best_full_eval_trial_score: float = 0.0

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

    # -- Compatibility properties for tests ------------------------------------

    @property
    def _known_candidates(self) -> dict[str, str]:
        return self._tracker._known_candidates

    @property
    def _candidate_parents(self) -> dict[str, list[str]]:
        return self._tracker._candidate_parents

    @property
    def _baseline_candidate_id(self) -> str | None:
        return self._tracker._baseline_candidate_id

    @property
    def _seed_candidate_key(self) -> str | None:
        return self._tracker._seed_candidate_key

    @property
    def _gepa_idx_to_candidate_id(self) -> dict[int, str]:
        return self._tracker._gepa_idx_to_candidate_id

    @_gepa_idx_to_candidate_id.setter
    def _gepa_idx_to_candidate_id(self, value: dict[int, str]) -> None:
        self._tracker._gepa_idx_to_candidate_id = value

    @property
    def _current_step(self) -> int:
        return self._tracker._current_step

    @_current_step.setter
    def _current_step(self, value: int) -> None:
        self._tracker._current_step = value

    @property
    def _selected_parent_id(self) -> str | None:
        return self._tracker._selected_parent_id

    @_selected_parent_id.setter
    def _selected_parent_id(self, value: str | None) -> None:
        self._tracker._selected_parent_id = value

    @property
    def _pending_eval_parent_ids(self) -> list[int] | None:
        return self._tracker._pending_eval_parent_ids

    @_pending_eval_parent_ids.setter
    def _pending_eval_parent_ids(self, value: list[int] | None) -> None:
        self._tracker._pending_eval_parent_ids = value

    @property
    def _pending_eval_capture_traces(self) -> bool | None:
        return self._tracker._pending_eval_capture_traces

    @_pending_eval_capture_traces.setter
    def _pending_eval_capture_traces(self, value: bool | None) -> None:
        self._tracker._pending_eval_capture_traces = value

    @property
    def _pending_eval_candidate_idx(self) -> int | None:
        return self._tracker._pending_eval_candidate_idx

    @_pending_eval_candidate_idx.setter
    def _pending_eval_candidate_idx(self, value: int | None) -> None:
        self._tracker._pending_eval_candidate_idx = value

    @property
    def _pending_merge_parent_ids(self) -> list[int] | None:
        return self._tracker._pending_merge_parent_ids

    @_pending_merge_parent_ids.setter
    def _pending_merge_parent_ids(self, value: list[int] | None) -> None:
        self._tracker._pending_merge_parent_ids = value

    @property
    def _reflection_log(self) -> list[dict[str, Any]]:
        return self._proposer.reflection_log if self._proposer else []

    @property
    def _reflection_prompt_template(self) -> str | None:
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
                if i < len(scores):
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

        effective_capture_traces = (
            self._tracker.consume_pending_capture_traces()
            if self._tracker._pending_eval_capture_traces is not None
            else capture_traces
        )

        existing_candidate_id = self._tracker.get_existing_candidate_id(key)
        eval_purpose = self._tracker.determine_eval_purpose(key, effective_capture_traces)
        parent_candidate_ids = self._tracker.resolve_parent_ids(key)

        dataset_item_ids = [
            str(inst.get("id"))
            for inst in batch
            if inst.get("id") is not None
        ]

        config = self._config_builder(candidate)

        batch_index = self._tracker._current_step if self._tracker._current_step >= 0 else None

        if self._full_dataset_size is None:
            self._full_dataset_size = len(batch)

        is_full_eval = len(batch) >= self._full_dataset_size
        experiment_type = None if is_full_eval else "mini-batch"

        logger.debug(
            "[adapter.evaluate] eval_purpose=%s batch_index=%s num_items=%d "
            "capture_traces=%s candidate_id=%s parent_ids=%s is_full=%s",
            eval_purpose,
            batch_index,
            len(batch),
            effective_capture_traces,
            existing_candidate_id,
            parent_candidate_ids,
            is_full_eval,
        )

        trial, raw_result = self._evaluation_adapter.evaluate_with_details(
            config=config,
            dataset_item_ids=dataset_item_ids,
            parent_candidate_ids=parent_candidate_ids,
            candidate_id=existing_candidate_id,
            batch_index=batch_index,
            num_items=len(batch),
            capture_traces=effective_capture_traces,
            eval_purpose=eval_purpose,
            experiment_type=experiment_type,
        )

        self._tracker.clear_pending_eval()

        if trial is not None:
            self._tracker.record_trial(key, trial, parent_candidate_ids, effective_capture_traces)
            if is_full_eval and trial.score > self.best_full_eval_trial_score:
                self.best_full_eval_trial_score = trial.score

        per_item = _extract_per_item_feedback(raw_result)
        self._last_per_item_feedback = per_item

        if self._batch_sampler is not None and is_full_eval:
            self._batch_sampler.update_scores(per_item)
            self._batch_sampler.update_assertion_failures(per_item)

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

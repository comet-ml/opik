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

import json
import logging
import re
from collections.abc import Sequence
from typing import Any, TYPE_CHECKING

logger = logging.getLogger(__name__)

if TYPE_CHECKING:
    from gepa.core.adapter import EvaluationBatch
    from opik_optimizer_framework.evaluation_adapter import EvaluationAdapter
    from opik_optimizer_framework.types import CandidateConfig, TrialResult


DatasetItem = dict[str, Any]


def _extract_template_variables(messages: list[dict[str, str]]) -> list[str]:
    """Extract {variable} placeholder names from prompt messages."""
    variables: list[str] = []
    seen: set[str] = set()
    for msg in messages:
        for match in re.findall(r"\{(\w+)\}", msg.get("content", "")):
            if match not in seen:
                seen.add(match)
                variables.append(match)
    return variables


def build_seed_candidate(prompt_messages: list[dict[str, str]]) -> dict[str, str]:
    """Build the initial GEPA seed candidate from the framework's prompt_messages list.

    Keys are formatted as ``{role}_{index}`` to match the rebuild logic.
    """
    seed: dict[str, str] = {}
    for idx, msg in enumerate(prompt_messages):
        key = f"{msg['role']}_{idx}"
        seed[key] = msg.get("content", "")
    return seed


def rebuild_prompt_messages(
    base_messages: list[dict[str, str]],
    candidate: dict[str, str],
) -> list[dict[str, str]]:
    """Rebuild prompt messages from GEPA candidate, falling back to originals."""
    messages: list[dict[str, str]] = []
    for idx, msg in enumerate(base_messages):
        key = f"{msg['role']}_{idx}"
        content = candidate.get(key, msg.get("content", ""))
        messages.append({"role": msg["role"], "content": content})
    return messages


def _extract_per_item_feedback(raw_result: Any) -> dict[str, dict[str, Any]]:
    """Extract per-item feedback from the raw EvaluationResult.

    Returns a dict keyed by dataset_item_id with:
      - output: the LLM output text
      - score: aggregate score (1.0 if all assertions passed, 0.0 otherwise)
      - assertions: list of dicts with name, value, reason per assertion
    """
    feedback: dict[str, dict[str, Any]] = {}
    if raw_result is None or not hasattr(raw_result, "test_results"):
        return feedback

    for test_result in raw_result.test_results:
        item_id = test_result.test_case.dataset_item_id
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

        item_score = min(scores) if scores else 0.0

        feedback[str(item_id)] = {
            "output": output_text,
            "score": item_score,
            "assertions": assertions,
        }

    return feedback


def _candidate_key(candidate: dict[str, str]) -> str:
    """Deterministic string key for a GEPA candidate dict."""
    return json.dumps(candidate, sort_keys=True)


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
    """Adapter that bridges GEPA's evaluate/reflect interface to the framework.

    Uses two complementary mechanisms for tracking evaluation context:

    1. **Callbacks** (via ``GEPAProgressCallback``): ``on_evaluation_start``
       fires RIGHT BEFORE each ``evaluate()`` call during the main loop,
       providing authoritative parent_ids, capture_traces, and candidate_idx.

    2. **Initialization fallback**: The seed eval (during ``initialize_gepa_state``)
       happens before callbacks are active. The adapter detects this via
       ``_current_step < 0`` and labels it ``eval_purpose="initialization"``.
    """

    propose_new_texts = None

    def __init__(
        self,
        base_messages: list[dict[str, str]],
        baseline_config: CandidateConfig,
        evaluation_adapter: EvaluationAdapter,
    ) -> None:
        self._base_messages = base_messages
        self._baseline_config = baseline_config
        self._evaluation_adapter = evaluation_adapter
        self._last_per_item_feedback: dict[str, dict[str, Any]] = {}

        # Candidate tracking
        self._known_candidates: dict[str, str] = {}
        self._candidate_parents: dict[str, list[str]] = {}
        self._baseline_candidate_id: str | None = None
        self._seed_candidate_key: str | None = None

        # GEPA index → framework candidate_id mapping
        self._gepa_idx_to_candidate_id: dict[int, str] = {}

        # Step/iteration tracking (set by callbacks)
        self._current_step: int = -1
        self._selected_parent_id: str | None = None

        # Pending eval metadata (set by on_evaluation_start, consumed by evaluate)
        self._pending_eval_parent_ids: list[int] | None = None
        self._pending_eval_capture_traces: bool | None = None
        self._pending_eval_candidate_idx: int | None = None
        self._pending_merge_parent_ids: list[int] | None = None

    def register_baseline(
        self,
        seed_candidate: dict[str, str],
        baseline_candidate_id: str,
    ) -> None:
        """Register the baseline so GEPA's initialization reuses the same candidate_id.

        Pre-seeds ``_known_candidates`` so the initialization eval (before
        iterations) reuses the baseline's candidate_id at step 0.
        """
        key = _candidate_key(seed_candidate)
        self._known_candidates[key] = baseline_candidate_id
        self._candidate_parents[baseline_candidate_id] = []
        self._baseline_candidate_id = baseline_candidate_id
        self._seed_candidate_key = key
        self._gepa_idx_to_candidate_id[0] = baseline_candidate_id

    # -- Callback handlers (called by GEPAProgressCallback) -------------------

    def _on_new_step(self, iteration: int) -> None:
        self._current_step = iteration
        self._selected_parent_id = None
        self._pending_merge_parent_ids = None
        self._pending_eval_parent_ids = None
        self._pending_eval_capture_traces = None
        self._pending_eval_candidate_idx = None

    def _on_candidate_selected(self, candidate_idx: int) -> None:
        self._selected_parent_id = self._gepa_idx_to_candidate_id.get(
            candidate_idx
        )

    def _on_evaluation_start(
        self,
        candidate_idx: int | None,
        parent_ids: Sequence[int],
        capture_traces: bool,
    ) -> None:
        self._pending_eval_candidate_idx = candidate_idx
        self._pending_eval_parent_ids = list(parent_ids)
        self._pending_eval_capture_traces = capture_traces

    def _on_valset_evaluated(
        self, candidate_idx: int, candidate: dict[str, str],
    ) -> None:
        key = _candidate_key(candidate)
        fw_id = self._known_candidates.get(key)
        if fw_id is not None:
            self._gepa_idx_to_candidate_id[candidate_idx] = fw_id

    def _on_merge_accepted(self, parent_ids: Sequence[int]) -> None:
        self._pending_merge_parent_ids = list(parent_ids)

    # -- Parent resolution ----------------------------------------------------

    def _resolve_parent_ids(
        self, key: str,
    ) -> list[str]:
        """Resolve parent candidate IDs using callback metadata and tracking maps.

        Priority:
        1. Merge parents (from on_merge_accepted)
        2. Pre-eval parents (from on_evaluation_start, resolved via gepa_idx map)
        3. Persistent parents (from _candidate_parents for known candidates)
        4. Selected parent (from on_candidate_selected)
        5. Baseline fallback
        """
        if self._pending_merge_parent_ids is not None:
            resolved = [
                self._gepa_idx_to_candidate_id[idx]
                for idx in self._pending_merge_parent_ids
                if idx in self._gepa_idx_to_candidate_id
            ]
            self._pending_merge_parent_ids = None
            if resolved:
                return resolved

        if self._pending_eval_parent_ids is not None:
            resolved = [
                self._gepa_idx_to_candidate_id[idx]
                for idx in self._pending_eval_parent_ids
                if idx in self._gepa_idx_to_candidate_id
            ]
            if resolved:
                return resolved

        known_id = self._known_candidates.get(key)
        if known_id is not None and known_id in self._candidate_parents:
            return self._candidate_parents[known_id]

        if self._selected_parent_id is not None:
            return [self._selected_parent_id]

        if self._baseline_candidate_id is not None:
            return [self._baseline_candidate_id]

        return []

    def _determine_eval_purpose(
        self, key: str, capture_traces: bool,
    ) -> str:
        """Determine eval_purpose from callback metadata or fallback detection."""
        if self._current_step < 0:
            return "initialization"

        if capture_traces:
            return "exploration:minibatch"

        is_known = key in self._known_candidates
        if not is_known:
            return "exploration:mutation"

        return "validation"

    # -- Core evaluate/reflect ------------------------------------------------

    def _record_trial(
        self,
        key: str,
        trial: TrialResult,
        parent_candidate_ids: list[str],
        capture_traces: bool,
    ) -> None:
        """Update candidate tracking maps after a successful evaluation."""
        is_new = trial.candidate_id not in self._candidate_parents
        self._known_candidates[key] = trial.candidate_id
        if is_new:
            self._candidate_parents[trial.candidate_id] = parent_candidate_ids

        if capture_traces:
            self._selected_parent_id = trial.candidate_id

        logger.debug(
            "[adapter.evaluate] result: score=%.4f candidate_id=%s "
            "is_new=%s parents=%s",
            trial.score,
            trial.candidate_id[:8],
            is_new,
            self._candidate_parents.get(trial.candidate_id, []),
        )

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
            output_text = item_feedback.get("output", "")
            score = item_feedback.get("score", 0.0)

            outputs.append({"output": output_text})
            scores.append(score)

            if trajectories is not None:
                trajectories.append(
                    {
                        "input": inst,
                        "output": output_text,
                        "score": score,
                        "assertions": item_feedback.get("assertions", []),
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
        """Evaluate a GEPA candidate against a batch of data instances.

        Delegates to the framework's EvaluationAdapter to get real scores
        from the evaluation suite (LLM judge assertions, etc.).
        """
        key = _candidate_key(candidate)

        # Use callback-provided capture_traces if available
        effective_capture_traces = (
            self._pending_eval_capture_traces
            if self._pending_eval_capture_traces is not None
            else capture_traces
        )

        existing_candidate_id = self._known_candidates.get(key)
        eval_purpose = self._determine_eval_purpose(key, effective_capture_traces)
        parent_candidate_ids = self._resolve_parent_ids(key)

        prompt_messages = rebuild_prompt_messages(self._base_messages, candidate)
        dataset_item_ids = [
            str(inst.get("id"))
            for inst in batch
            if inst.get("id") is not None
        ]

        config = {**self._baseline_config, "prompt_messages": prompt_messages}

        batch_index = self._current_step if self._current_step >= 0 else None

        logger.debug(
            "[adapter.evaluate] eval_purpose=%s batch_index=%s num_items=%d "
            "capture_traces=%s candidate_id=%s parent_ids=%s",
            eval_purpose,
            batch_index,
            len(batch),
            effective_capture_traces,
            existing_candidate_id,
            parent_candidate_ids,
        )

        experiment_type = (
            "mini-batch" if eval_purpose == "exploration:minibatch" else None
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

        # Clear pending eval metadata (consumed)
        self._pending_eval_parent_ids = None
        self._pending_eval_capture_traces = None
        self._pending_eval_candidate_idx = None

        if trial is not None:
            self._record_trial(key, trial, parent_candidate_ids, effective_capture_traces)

        per_item = _extract_per_item_feedback(raw_result)
        self._last_per_item_feedback = per_item

        return self._build_evaluation_batch(
            batch, per_item, trial, effective_capture_traces,
        )

    def make_reflective_dataset(
        self,
        candidate: dict[str, str],
        eval_batch: EvaluationBatch,
        components_to_update: list[str],
    ) -> dict[str, list[dict[str, Any]]]:
        """Build the feedback dataset for GEPA's reflection LLM.

        Uses structured assertion results from the evaluation suite to provide
        actionable feedback — only failed assertions are shown with their names.
        Input fields are extracted dynamically from the prompt template variables.
        """
        if not components_to_update:
            components_to_update = [
                key
                for key in candidate.keys()
                if not key.startswith("_") and key not in ("source", "id")
            ]

        trajectories = eval_batch.trajectories or []
        template_vars = _extract_template_variables(self._base_messages)

        def _build_inputs(dataset_item: dict[str, Any]) -> dict[str, str]:
            inputs: dict[str, str] = {}
            for var in template_vars:
                val = dataset_item.get(var)
                if val is not None:
                    inputs[var] = str(val)
            if not inputs:
                for k, v in dataset_item.items():
                    if k not in ("id", "assertions", "metadata") and isinstance(v, str):
                        inputs[k] = v
            return inputs

        def _build_feedback(
            score: float, assertions: list[dict[str, Any]],
        ) -> str:
            failed = [
                a for a in assertions
                if a["value"] < 1.0 and a.get("reason")
            ]
            if failed:
                lines = [f"Score={score:.1f}. Failed assertions:"]
                for a in failed:
                    lines.append(f"- {a['name']}: {a['reason']}")
                return "\n".join(lines)
            return f"Score={score:.1f}. All assertions passed."

        def _records() -> list[dict[str, Any]]:
            result = []
            for traj in trajectories:
                dataset_item = traj.get("input", {})
                output_text = traj.get("output", "")
                score = traj.get("score", 0.0)
                assertions = traj.get("assertions", [])

                result.append(
                    {
                        "Inputs": _build_inputs(dataset_item),
                        "Generated Outputs": output_text,
                        "Feedback": _build_feedback(score, assertions),
                    }
                )
            return result

        records = _records()
        return {component: list(records) for component in components_to_update}

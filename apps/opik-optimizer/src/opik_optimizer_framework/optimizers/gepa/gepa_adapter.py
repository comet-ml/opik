"""GEPA adapter bridging GEPA's evaluation interface to the framework's EvaluationAdapter."""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from typing import Any, TYPE_CHECKING

logger = logging.getLogger(__name__)

try:
    from gepa.core.adapter import EvaluationBatch, GEPAAdapter
except ImportError:
    GEPAAdapter = None  # type: ignore[assignment,misc]
    EvaluationBatch = None  # type: ignore[assignment,misc]

if TYPE_CHECKING:
    from opik_optimizer_framework.evaluation_adapter import EvaluationAdapter
    from opik_optimizer_framework.event_emitter import EventEmitter


@dataclass
class OpikDataInst:
    """Data instance handed to GEPA.

    Preserves the original dataset item dict so prompt formatting
    can use all available fields.
    """

    input_text: str
    answer: str
    additional_context: dict[str, str]
    opik_item: dict[str, Any]


def build_data_insts(
    dataset_items: list[dict[str, Any]],
    input_key: str,
    output_key: str,
) -> list[OpikDataInst]:
    """Convert raw dataset item dicts into OpikDataInst objects for GEPA."""
    insts: list[OpikDataInst] = []
    for item in dataset_items:
        additional_context: dict[str, str] = {}
        if "context" in item and isinstance(item["context"], str):
            additional_context["context"] = item["context"]
        insts.append(
            OpikDataInst(
                input_text=str(item.get(input_key, "")),
                answer=str(item.get(output_key, "")),
                additional_context=additional_context,
                opik_item=item,
            )
        )
    return insts


def infer_dataset_keys(items: list[dict[str, Any]]) -> tuple[str, str]:
    """Infer input and output keys from a list of dataset items."""
    if not items:
        return "text", "label"
    sample = items[0]
    output_candidates = ["label", "answer", "output", "expected_output"]
    output_key = next((k for k in output_candidates if k in sample), "label")
    excluded = {output_key, "id", "metadata"}
    input_key = next((k for k in sample.keys() if k not in excluded), "text")
    return input_key, output_key


def _ensure_gepa_available() -> None:
    if GEPAAdapter is None:
        raise ImportError(
            "The 'gepa' package is required for GepaOptimizer. "
            "Install it with: pip install 'gepa>=0.1.0'"
        )


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
      - reasons: list of reason strings from ScoreResult objects
    """
    feedback: dict[str, dict[str, Any]] = {}
    if raw_result is None or not hasattr(raw_result, "test_results"):
        return feedback

    for test_result in raw_result.test_results:
        item_id = test_result.test_case.dataset_item_id
        task_output = getattr(test_result.test_case, "task_output", {}) or {}
        output_text = str(task_output.get("output", ""))

        reasons = []
        scores = []
        for sr in test_result.score_results:
            scores.append(float(getattr(sr, "value", 0.0)))
            reason = getattr(sr, "reason", None)
            if reason:
                reasons.append(reason)

        item_score = min(scores) if scores else 0.0

        feedback[str(item_id)] = {
            "output": output_text,
            "score": item_score,
            "reasons": reasons,
        }

    return feedback


def _candidate_key(candidate: dict[str, str]) -> str:
    """Deterministic string key for a GEPA candidate dict."""
    return json.dumps(candidate, sort_keys=True)


class GEPAProgressCallback:
    """Bridges GEPA's lifecycle events to the framework's EventEmitter and adapter.

    Implements the GEPACallback protocol (duck-typed — implement only needed methods).
    GEPA dispatches events via ``notify_callbacks(callbacks, method_name, event)``.

    Event flow per iteration:
      on_iteration_start → on_candidate_selected → on_minibatch_sampled →
      on_evaluation_start → adapter.evaluate() → on_evaluation_end →
      (reflection) → on_evaluation_start → adapter.evaluate() → on_evaluation_end →
      (if accepted) on_candidate_accepted + on_valset_evaluated
    """

    def __init__(
        self,
        event_emitter: EventEmitter,
        adapter: FrameworkGEPAAdapter,
        total_steps: int = 0,
    ) -> None:
        self._event_emitter = event_emitter
        self._adapter = adapter
        self._total_steps = total_steps

    def on_iteration_start(self, event: dict[str, Any]) -> None:
        iteration = event["iteration"]
        logger.info("[callback] on_iteration_start: iteration=%d", iteration)
        self._adapter._on_new_step(iteration)
        self._event_emitter.on_step_started(iteration, self._total_steps)

    def on_candidate_selected(self, event: dict[str, Any]) -> None:
        candidate_idx = event["candidate_idx"]
        logger.info(
            "[callback] on_candidate_selected: iteration=%d candidate_idx=%d score=%.4f",
            event.get("iteration", -1), candidate_idx, event.get("score", 0.0),
        )
        self._adapter._on_candidate_selected(candidate_idx)

    def on_evaluation_start(self, event: dict[str, Any]) -> None:
        """Fires RIGHT BEFORE adapter.evaluate(). Sets metadata for the upcoming eval."""
        candidate_idx = event.get("candidate_idx")
        parent_ids = list(event.get("parent_ids", []))
        capture_traces = event.get("capture_traces", False)
        logger.info(
            "[callback] on_evaluation_start: iteration=%d candidate_idx=%s "
            "parent_ids=%s capture_traces=%s batch_size=%d is_seed=%s",
            event.get("iteration", -1), candidate_idx, parent_ids,
            capture_traces, event.get("batch_size", 0),
            event.get("is_seed_candidate", False),
        )
        self._adapter._on_evaluation_start(
            candidate_idx=candidate_idx,
            parent_ids=parent_ids,
            capture_traces=capture_traces,
        )

    def on_evaluation_end(self, event: dict[str, Any]) -> None:
        logger.info(
            "[callback] on_evaluation_end: iteration=%d candidate_idx=%s "
            "scores=%s has_trajectories=%s",
            event.get("iteration", -1), event.get("candidate_idx"),
            event.get("scores", []), event.get("has_trajectories", False),
        )

    def on_candidate_accepted(self, event: dict[str, Any]) -> None:
        logger.info(
            "[callback] on_candidate_accepted: iteration=%d new_idx=%d "
            "score=%.4f parent_ids=%s",
            event.get("iteration", -1), event.get("new_candidate_idx", -1),
            event.get("new_score", 0.0), list(event.get("parent_ids", [])),
        )

    def on_candidate_rejected(self, event: dict[str, Any]) -> None:
        logger.info(
            "[callback] on_candidate_rejected: iteration=%d old=%.4f new=%.4f reason=%s",
            event.get("iteration", -1), event.get("old_score", 0.0),
            event.get("new_score", 0.0), event.get("reason", ""),
        )

    def on_valset_evaluated(self, event: dict[str, Any]) -> None:
        candidate_idx = event["candidate_idx"]
        logger.info(
            "[callback] on_valset_evaluated: iteration=%d candidate_idx=%d "
            "avg_score=%.4f num_items=%d is_best=%s parent_ids=%s",
            event.get("iteration", -1), candidate_idx,
            event.get("average_score", 0.0),
            event.get("num_examples_evaluated", 0),
            event.get("is_best_program", False),
            list(event.get("parent_ids", [])),
        )
        self._adapter._on_valset_evaluated(candidate_idx, event["candidate"])

    def on_merge_accepted(self, event: dict[str, Any]) -> None:
        parent_ids = list(event.get("parent_ids", []))
        logger.info(
            "[callback] on_merge_accepted: iteration=%d new_idx=%d parent_ids=%s",
            event.get("iteration", -1), event.get("new_candidate_idx", -1),
            parent_ids,
        )
        self._adapter._on_merge_accepted(parent_ids)

    def on_optimization_end(self, event: dict[str, Any]) -> None:
        total_iterations = event.get("total_iterations", 0)
        total_metric_calls = event.get("total_metric_calls", 0)
        logger.info(
            "[callback] on_optimization_end: %d iterations, %d metric calls",
            total_iterations,
            total_metric_calls,
        )


class FrameworkGEPAAdapter:
    """Adapter that bridges GEPA's evaluate/reflect interface to the framework.

    Calls ``evaluation_adapter.evaluate_with_details()`` during GEPA's
    internal optimization loop to get real scores from the evaluation suite.

    Metadata flow:
    - ``on_evaluation_start`` fires RIGHT BEFORE each ``evaluate()`` call with
      ``candidate_idx``, ``parent_ids``, and ``capture_traces`` — we store these
      as ``_pending_eval_*`` fields and consume them in ``evaluate()``.
    - ``on_valset_evaluated`` fires AFTER valset eval — maps gepa_idx to framework_id.
    - ``on_merge_accepted`` fires for merges — sets parent IDs for the next eval.
    """

    # Let GEPA use its default InstructionProposalSignature for mutation.
    propose_new_texts = None

    def __init__(
        self,
        base_messages: list[dict[str, str]],
        model: str,
        model_parameters: dict[str, Any],
        evaluation_adapter: EvaluationAdapter,
    ) -> None:
        _ensure_gepa_available()
        self._base_messages = base_messages
        self._model = model
        self._model_parameters = model_parameters
        self._evaluation_adapter = evaluation_adapter
        self._last_per_item_feedback: dict[str, dict[str, Any]] = {}

        # Maps GEPA candidate index → framework candidate_id
        self._gepa_idx_to_candidate_id: dict[int, str] = {}
        # Maps candidate_key → framework candidate_id (latest eval)
        self._known_candidates: dict[str, str] = {}
        # Persistent parent mapping: candidate_id → parent_candidate_ids
        self._candidate_parents: dict[str, list[str]] = {}
        self._current_step = -1

        # Pre-eval metadata set by on_evaluation_start (consumed in evaluate())
        self._pending_eval_parent_ids: list[int] | None = None
        self._pending_eval_capture_traces: bool | None = None
        self._pending_eval_candidate_idx: int | None = None

        # Parent framework ID from on_candidate_selected (pre-eval)
        self._selected_parent_id: str | None = None
        # Parent IDs from on_merge_accepted (pre-eval for merged candidate)
        self._pending_merge_parent_ids: list[str] | None = None

    def register_baseline(
        self, seed_candidate: dict[str, str], baseline_candidate_id: str,
    ) -> None:
        """Pre-seed the candidate mapping with the baseline's candidate_id.

        Called before GEPA starts so its initial seed evaluation reuses the
        same candidate_id that the orchestrator's baseline eval produced.
        Re-evaluations of the seed by GEPA will carry the baseline as parent.
        """
        key = _candidate_key(seed_candidate)
        self._known_candidates[key] = baseline_candidate_id
        self._candidate_parents[baseline_candidate_id] = [baseline_candidate_id]

    def _on_new_step(self, step: int) -> None:
        """Called by GEPAProgressCallback on each iteration start."""
        self._current_step = step
        self._selected_parent_id = None
        self._pending_merge_parent_ids = None
        self._pending_eval_parent_ids = None
        self._pending_eval_capture_traces = None
        self._pending_eval_candidate_idx = None

    def _on_candidate_selected(self, candidate_idx: int) -> None:
        """Called BEFORE evaluation. Records which candidate was selected as parent."""
        framework_id = self._gepa_idx_to_candidate_id.get(candidate_idx)
        self._selected_parent_id = framework_id
        logger.debug(
            "Candidate selected: gepa_idx=%d → framework_id=%s",
            candidate_idx,
            framework_id,
        )

    def _on_evaluation_start(
        self,
        candidate_idx: int | None,
        parent_ids: list[int],
        capture_traces: bool,
    ) -> None:
        """Called RIGHT BEFORE adapter.evaluate(). Stores metadata for the upcoming eval."""
        self._pending_eval_candidate_idx = candidate_idx
        self._pending_eval_parent_ids = parent_ids
        self._pending_eval_capture_traces = capture_traces

    def _on_valset_evaluated(
        self,
        candidate_idx: int | None,
        candidate: dict[str, str],
    ) -> None:
        """Called AFTER evaluation. Maps gepa_idx → framework candidate_id."""
        if candidate_idx is None:
            return
        key = _candidate_key(candidate)
        framework_id = self._known_candidates.get(key)
        if framework_id is not None:
            self._gepa_idx_to_candidate_id[candidate_idx] = framework_id
            logger.debug(
                "Valset evaluated: gepa_idx=%d → framework_id=%s",
                candidate_idx,
                framework_id,
            )

    def _on_merge_accepted(self, gepa_parent_indices: list[int]) -> None:
        """Called when GEPA merges candidates. Stores parent IDs for the next eval."""
        framework_parent_ids = []
        for idx in gepa_parent_indices:
            fid = self._gepa_idx_to_candidate_id.get(idx)
            if fid is not None:
                framework_parent_ids.append(fid)
        if framework_parent_ids:
            self._pending_merge_parent_ids = framework_parent_ids

    def _resolve_parent_ids(
        self, candidate: dict[str, str],
    ) -> list[str] | None:
        """Determine parent_candidate_ids for the given candidate.

        Priority:
        1. Merge parent IDs (from on_merge_accepted callback)
        2. Pre-eval parent IDs from on_evaluation_start (GEPA indices → framework IDs)
        3. For known candidates (re-eval): look up persistent parents
        4. For new candidates (mutation result): parent is the selected candidate
        """
        if self._pending_merge_parent_ids is not None:
            ids = self._pending_merge_parent_ids
            self._pending_merge_parent_ids = None
            return ids

        # Use parent_ids from on_evaluation_start if available and non-empty
        if self._pending_eval_parent_ids is not None:
            framework_ids = []
            for idx in self._pending_eval_parent_ids:
                fid = self._gepa_idx_to_candidate_id.get(idx)
                if fid is not None:
                    framework_ids.append(fid)
            if framework_ids:
                return framework_ids
            # Empty resolved list — fall through to persistent parents lookup

        key = _candidate_key(candidate)
        known_id = self._known_candidates.get(key)
        if known_id is not None:
            stored = self._candidate_parents.get(known_id)
            if stored is not None:
                return stored

        # New candidate — parent is the one selected at the start of this iteration
        if self._selected_parent_id is not None:
            return [self._selected_parent_id]
        return None

    def _determine_eval_purpose(self) -> str:
        """Determine the evaluation purpose based on current adapter state."""
        if self._current_step < 0:
            return "initialization"
        if self._pending_eval_capture_traces is not None:
            if self._pending_eval_capture_traces:
                return "exploration:minibatch"
            return "exploration:mutation"
        return "validation"

    def _record_trial_lineage(
        self,
        key: str,
        trial: Any,
        parent_candidate_ids: list[str] | None,
    ) -> None:
        """Update candidate tracking maps after a successful evaluation."""
        is_new = trial.candidate_id not in self._candidate_parents
        self._known_candidates[key] = trial.candidate_id
        if is_new:
            self._candidate_parents[trial.candidate_id] = (
                parent_candidate_ids or []
            )
        logger.debug(
            "[adapter.evaluate] result: trial_id=%s score=%.4f "
            "candidate_id=%s is_new_candidate=%s stored_parents=%s",
            trial.candidate_id[:8], trial.score,
            trial.candidate_id[:8], is_new,
            self._candidate_parents.get(trial.candidate_id, []),
        )

    def _build_evaluation_batch(
        self,
        batch: list[OpikDataInst],
        per_item: dict[str, dict[str, Any]],
        trial: Any | None,
        capture_traces: bool,
    ) -> EvaluationBatch:
        """Convert per-item feedback into GEPA's EvaluationBatch format."""
        outputs: list[dict[str, Any]] = []
        scores: list[float] = []
        trajectories: list[dict[str, Any]] | None = [] if capture_traces else None

        for inst in batch:
            item_id = str(inst.opik_item.get("id", ""))
            item_feedback = per_item.get(item_id, {})
            output_text = item_feedback.get("output", "")
            score = item_feedback.get("score", 0.0)

            outputs.append({"output": output_text})
            scores.append(score)

            if trajectories is not None:
                trajectories.append(
                    {
                        "input": inst.opik_item,
                        "output": output_text,
                        "score": score,
                        "reasons": item_feedback.get("reasons", []),
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
        batch: list[OpikDataInst],
        candidate: dict[str, str],
        capture_traces: bool = False,
    ) -> EvaluationBatch:
        """Evaluate a GEPA candidate against a batch of data instances.

        Delegates to the framework's EvaluationAdapter to get real scores
        from the evaluation suite (LLM judge assertions, etc.).
        """
        from opik_optimizer_framework.types import CandidateConfig

        key = _candidate_key(candidate)
        parent_candidate_ids = self._resolve_parent_ids(candidate)
        existing_candidate_id = self._known_candidates.get(key)

        effective_capture_traces = (
            self._pending_eval_capture_traces
            if self._pending_eval_capture_traces is not None
            else capture_traces
        )

        prompt_messages = rebuild_prompt_messages(self._base_messages, candidate)
        dataset_item_ids = [
            str(inst.opik_item.get("id"))
            for inst in batch
            if inst.opik_item.get("id") is not None
        ]

        config = CandidateConfig(
            prompt_messages=prompt_messages,
            model=self._model,
            model_parameters=self._model_parameters,
        )

        batch_index = self._current_step if self._current_step >= 0 else None
        eval_purpose = self._determine_eval_purpose()

        trial_count = getattr(self._evaluation_adapter, "trial_count", "?")
        logger.debug(
            "[adapter.evaluate] step_index=%s batch_index=%s num_items=%d "
            "capture_traces=%s candidate_id=%s parent_ids=%s "
            "eval_purpose=%s",
            trial_count, batch_index, len(batch),
            effective_capture_traces, existing_candidate_id,
            parent_candidate_ids, eval_purpose,
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
        )

        if trial is not None:
            self._record_trial_lineage(key, trial, parent_candidate_ids)

        # Clear pending eval metadata (consumed)
        self._pending_eval_parent_ids = None
        self._pending_eval_capture_traces = None
        self._pending_eval_candidate_idx = None

        per_item = _extract_per_item_feedback(raw_result)
        self._last_per_item_feedback = per_item

        return self._build_evaluation_batch(
            batch, per_item, trial, capture_traces,
        )

    def make_reflective_dataset(
        self,
        candidate: dict[str, str],
        eval_batch: EvaluationBatch,
        components_to_update: list[str],
    ) -> dict[str, list[dict[str, Any]]]:
        """Build the feedback dataset for GEPA's reflection LLM.

        Uses ``reason`` fields from the evaluation suite's ScoreResult objects
        (e.g., LLM judge assertion explanations) to provide rich feedback.
        """
        if not components_to_update:
            components_to_update = [
                key
                for key in candidate.keys()
                if not key.startswith("_") and key not in ("source", "id")
            ]

        trajectories = eval_batch.trajectories or []

        def _records() -> list[dict[str, Any]]:
            result = []
            for traj in trajectories:
                dataset_item = traj.get("input", {})
                output_text = traj.get("output", "")
                score = traj.get("score", 0.0)
                reasons = traj.get("reasons", [])

                if reasons:
                    feedback = (
                        f"Score={score:.4f}. "
                        f"Evaluator feedback: {'; '.join(reasons)}"
                    )
                else:
                    expected = (
                        dataset_item.get("answer")
                        or dataset_item.get("label")
                        or dataset_item.get("expected_output")
                        or ""
                    )
                    feedback = (
                        f"Score={score:.4f}. "
                        f"Expected answer: {expected}"
                    )

                result.append(
                    {
                        "Inputs": {
                            "text": dataset_item.get("input")
                            or dataset_item.get("question")
                            or dataset_item.get("text")
                            or "",
                        },
                        "Generated Outputs": output_text,
                        "Feedback": feedback,
                    }
                )
            return result

        records = _records()
        return {component: list(records) for component in components_to_update}

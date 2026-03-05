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
from collections.abc import Mapping, Sequence
from typing import Any, TYPE_CHECKING

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

    def __init__(
        self,
        config_builder: Any,
        evaluation_adapter: EvaluationAdapter,
        reflection_lm: Any = None,
        reflection_prompt_template: str | None = None,
        batch_sampler: FailureAwareBatchSampler | None = None,
    ) -> None:
        self._config_builder = config_builder
        self._evaluation_adapter = evaluation_adapter
        self._reflection_lm = reflection_lm
        self._reflection_prompt_template = reflection_prompt_template
        self._batch_sampler = batch_sampler
        self._last_per_item_feedback: dict[str, dict[str, Any]] = {}
        self._reflection_log: list[dict[str, Any]] = []

        # Candidate tracking
        self._known_candidates: dict[str, str] = {}
        self._candidate_parents: dict[str, list[str]] = {}
        self._baseline_candidate_id: str | None = None
        self._seed_candidate_key: str | None = None
        self._full_dataset_size: int | None = None
        self.best_full_eval_trial_score: float = 0.0

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

        dataset_item_ids = [
            str(inst.get("id"))
            for inst in batch
            if inst.get("id") is not None
        ]

        config = self._config_builder(candidate)

        batch_index = self._current_step if self._current_step >= 0 else None

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

        # Clear pending eval metadata (consumed)
        self._pending_eval_parent_ids = None
        self._pending_eval_capture_traces = None
        self._pending_eval_candidate_idx = None

        if trial is not None:
            self._record_trial(key, trial, parent_candidate_ids, effective_capture_traces)
            if is_full_eval and trial.score > self.best_full_eval_trial_score:
                self.best_full_eval_trial_score = trial.score

        per_item = _extract_per_item_feedback(raw_result)
        self._last_per_item_feedback = per_item

        if self._batch_sampler is not None:
            if is_full_eval:
                self._batch_sampler.update_scores(per_item)
                self._batch_sampler.update_assertion_failures(per_item)
            else:
                self._batch_sampler.mark_seen(dataset_item_ids)

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

        When runs_per_item > 1, all runs are consolidated into a single
        record per input — this avoids repeating the same input N times
        and lets the reflection LLM compare runs side-by-side.
        Records are sorted by difficulty (most failures first).
        """
        if not components_to_update:
            components_to_update = [
                key
                for key in candidate.keys()
                if not key.startswith("_") and key not in ("source", "id")
            ]

        trajectories = eval_batch.trajectories or []

        def _build_inputs(dataset_item: dict[str, Any]) -> dict[str, str]:
            return {
                k: str(v) for k, v in dataset_item.items()
                if k != "id"
            }

        def _build_run_feedback(assertions: list[dict[str, Any]]) -> str:
            failed = [a for a in assertions if a["value"] < 1.0]
            passed = [a for a in assertions if a["value"] >= 1.0]
            lines = []
            if failed:
                lines.append("FAILED assertions (fix these):")
                for a in failed:
                    reason = a.get("reason", "")
                    lines.append(f"- Assertion: {a['name']}")
                    if reason:
                        lines.append(f"  Reason: {reason}")
            if passed:
                lines.append("PASSED assertions (preserve these):")
                for a in passed:
                    lines.append(f"- {a['name']}")
            return "\n".join(lines)

        records = []
        for traj in trajectories:
            dataset_item = traj.get("input", {})
            runs = traj.get("runs", [])
            total_runs = len(runs)
            inputs = _build_inputs(dataset_item)

            if total_runs <= 1:
                run = runs[0] if runs else {}
                assertions = run.get("assertions", [])
                max_failed = sum(1 for a in assertions if a["value"] < 1.0)
                records.append({
                    "Inputs": inputs,
                    "Generated Outputs": run.get("output", ""),
                    "Feedback": _build_run_feedback(assertions),
                    "_max_failed": max_failed,
                })
            else:
                run_sections = []
                max_failed = 0
                per_run_failed_names: list[set[str]] = []
                num_passed_runs = 0

                for run_idx, run in enumerate(runs):
                    assertions = run.get("assertions", [])
                    num_failed = sum(1 for a in assertions if a["value"] < 1.0)
                    max_failed = max(max_failed, num_failed)
                    failed_names = {a["name"] for a in assertions if a["value"] < 1.0}
                    per_run_failed_names.append(failed_names)
                    if num_failed == 0:
                        num_passed_runs += 1

                    section = f"[Run {run_idx + 1}/{total_runs}]\n"
                    section += f"Output: {run.get('output', '')}\n"
                    section += _build_run_feedback(assertions)
                    run_sections.append(section)

                consistent = set.intersection(*per_run_failed_names) if per_run_failed_names else set()
                summary_parts = [f"{num_passed_runs}/{total_runs} runs passed."]
                if consistent:
                    summary_parts.append(
                        f"Consistent failures: {', '.join(sorted(consistent))}"
                    )

                records.append({
                    "Inputs": inputs,
                    "Runs": "\n\n".join(run_sections),
                    "Summary": " ".join(summary_parts),
                    "_max_failed": max_failed,
                })

        if self._batch_sampler is not None:
            for record, traj in zip(records, trajectories):
                item_id = str(traj.get("input", {}).get("id", ""))
                streak = self._batch_sampler.get_failure_streak(item_id)
                if streak >= 1:
                    stuck = self._batch_sampler.get_failed_assertions(item_id)
                    if stuck:
                        record["Failure History"] = (
                            f"This item has failed {streak} consecutive iteration(s). "
                            f"Still-failing assertions: {', '.join(stuck)}. "
                            f"The current rules for these assertions are not working."
                        )

        records.sort(key=lambda r: r["_max_failed"], reverse=True)
        for r in records:
            del r["_max_failed"]

        return {component: list(records) for component in components_to_update}

    def _get_reflection_lm_callable(self) -> Any:
        """Return a callable LanguageModel from the stored reflection_lm.

        Handles both string model names (wrapped via litellm) and callables.
        """
        if callable(self._reflection_lm):
            return self._reflection_lm
        if isinstance(self._reflection_lm, str):
            import litellm
            model_name = self._reflection_lm

            def _lm(prompt: str | list[dict[str, str]]) -> str:
                if isinstance(prompt, str):
                    completion = litellm.completion(
                        model=model_name,
                        messages=[{"role": "user", "content": prompt}],
                    )
                else:
                    completion = litellm.completion(model=model_name, messages=prompt)
                return completion.choices[0].message.content
            return _lm
        raise ValueError(f"reflection_lm must be a string or callable, got {type(self._reflection_lm)}")

    def propose_new_texts(
        self,
        candidate: dict[str, str],
        reflective_dataset: Mapping[str, Sequence[Mapping[str, Any]]],
        components_to_update: list[str],
    ) -> dict[str, str]:
        """Propose improved texts using the reflection LLM.

        Adds the parameter name to <curr_param> so the reflection LLM knows
        which component it's optimizing. Logs all reflection calls for debugging.
        """
        from gepa.strategies.instruction_proposal import InstructionProposalSignature

        if self._reflection_lm is None:
            raise ValueError(
                "reflection_lm is required for propose_new_texts. "
                "Pass it via FrameworkGEPAAdapter constructor."
            )

        lm = self._get_reflection_lm_callable()
        new_texts: dict[str, str] = {}
        for name in components_to_update:
            if name not in reflective_dataset or not reflective_dataset.get(name):
                logger.info("Component '%s' not in reflective dataset, skipping.", name)
                continue

            current_instruction = f"Parameter: {name}\n{candidate[name]}"
            dataset_with_feedback = reflective_dataset[name]

            input_dict = {
                "current_instruction_doc": current_instruction,
                "dataset_with_feedback": dataset_with_feedback,
                "prompt_template": self._reflection_prompt_template,
            }

            rendered_prompt = InstructionProposalSignature.prompt_renderer(input_dict)

            log_entry: dict[str, Any] = {
                "component": name,
                "current_instruction": current_instruction,
                "dataset_with_feedback": [dict(d) for d in dataset_with_feedback],
                "rendered_prompt": rendered_prompt if isinstance(rendered_prompt, str) else str(rendered_prompt),
            }

            result = InstructionProposalSignature.run(
                lm=lm,
                input_dict=input_dict,
            )
            new_text = result["new_instruction"]
            prefix = f"Parameter: {name}\n"
            if new_text.startswith(prefix):
                new_text = new_text[len(prefix):]
            new_texts[name] = new_text

            log_entry["proposed_text"] = new_text
            self._reflection_log.append(log_entry)
            logger.info(
                "Reflection for '%s': proposed %d chars (was %d)",
                name, len(new_text), len(candidate[name]),
            )

        return new_texts

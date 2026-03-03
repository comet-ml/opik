from __future__ import annotations

import logging
from typing import Any

from opik_optimizer_framework.candidate_materializer import materialize_candidate
from opik_optimizer_framework.candidate_validator import validate_candidate
from opik_optimizer_framework.event_emitter import EventEmitter
from opik_optimizer_framework.experiment_executor import run_experiment_with_details
from opik_optimizer_framework.types import (
    CandidateConfig,
    OptimizationState,
    TrialResult,
)

logger = logging.getLogger(__name__)


class EvaluationAdapter:
    """Optimizer-facing boundary for evaluating candidates.

    Tracks trial count internally so optimizers don't need to manage step_index.
    """

    def __init__(
        self,
        client: object,
        dataset_name: str,
        optimization_id: str,
        metric_type: str,
        metric_parameters: dict[str, Any],
        state: OptimizationState,
        event_emitter: EventEmitter,
    ) -> None:
        self._client = client
        self._dataset_name = dataset_name
        self._optimization_id = optimization_id
        self._metric_type = metric_type
        self._metric_parameters = metric_parameters
        self._state = state
        self._event_emitter = event_emitter
        self._trial_count = 0
        self._candidate_step_index: dict[str, int] = {}
        self._last_emitted_step = -1

    @property
    def trial_count(self) -> int:
        """Number of trials evaluated so far (read-only)."""
        return self._trial_count

    def _resolve_step_index(self, parent_candidate_ids: list[str] | None) -> int:
        """Derive step_index from parent lineage: max parent step + 1.

        If no parents are provided or none are tracked, defaults to 0.
        """
        if not parent_candidate_ids:
            return 0
        parent_steps = [
            self._candidate_step_index[pid]
            for pid in parent_candidate_ids
            if pid in self._candidate_step_index
        ]
        if not parent_steps:
            return 0
        return max(parent_steps) + 1

    def evaluate(
        self,
        config: CandidateConfig,
        dataset_item_ids: list[str],
        parent_candidate_ids: list[str] | None = None,
        eval_purpose: str | None = None,
    ) -> TrialResult | None:
        """Evaluate a single candidate. Returns TrialResult or None if rejected."""
        trial, _ = self.evaluate_with_details(
            config=config,
            dataset_item_ids=dataset_item_ids,
            parent_candidate_ids=parent_candidate_ids,
            eval_purpose=eval_purpose,
        )
        return trial

    def evaluate_with_details(
        self,
        config: CandidateConfig,
        dataset_item_ids: list[str],
        parent_candidate_ids: list[str] | None = None,
        candidate_id: str | None = None,
        batch_index: int | None = None,
        num_items: int | None = None,
        capture_traces: bool | None = None,
        eval_purpose: str | None = None,
    ) -> tuple[TrialResult | None, Any]:
        """Evaluate a candidate and return both the TrialResult and the raw EvaluationResult.

        The raw result contains per-item test_results with ScoreResult objects
        that include ``reason`` fields from LLM judge assertions. Returns
        ``(None, None)`` if the candidate is rejected.
        """
        valid, reason = validate_candidate(config, self._state)
        if not valid:
            logger.warning("Candidate rejected: %s", reason)
            return None, None

        # step_index derived from parent lineage: parent's step + 1.
        # Re-evaluations of the same candidate reuse its step_index.
        if candidate_id is not None and candidate_id in self._candidate_step_index:
            step_index = self._candidate_step_index[candidate_id]
        else:
            step_index = self._resolve_step_index(parent_candidate_ids)

        if step_index != self._last_emitted_step:
            self._event_emitter.on_step_started(step_index)
            self._last_emitted_step = step_index

        self._trial_count += 1

        candidate = materialize_candidate(
            config,
            step_index=step_index,
            parent_candidate_ids=parent_candidate_ids,
            candidate_id=candidate_id,
        )

        trial, raw_result = run_experiment_with_details(
            client=self._client,
            candidate=candidate,
            dataset_name=self._dataset_name,
            dataset_item_ids=dataset_item_ids,
            optimization_id=self._optimization_id,
            metric_type=self._metric_type,
            metric_parameters=self._metric_parameters,
            batch_index=batch_index,
            num_items=num_items,
            capture_traces=capture_traces,
            eval_purpose=eval_purpose,
        )

        # Register candidate_id → step_index after trial so re-evals reuse the step.
        if trial is not None and trial.candidate_id not in self._candidate_step_index:
            self._candidate_step_index[trial.candidate_id] = step_index

        previous_best = self._state.best_trial
        self._state.trials.append(trial)
        if self._state.best_trial is None or trial.score > self._state.best_trial.score:
            self._state.best_trial = trial
        self._event_emitter.on_trial_completed(trial)

        if self._state.best_trial is trial and self._state.best_trial is not previous_best:
            self._event_emitter.on_best_candidate_changed(trial)

        return trial, raw_result

from __future__ import annotations

import logging
from typing import Any

from opik_optimizer_framework.candidate_materializer import materialize_candidate
from opik_optimizer_framework.candidate_validator import validate_candidate
from opik_optimizer_framework.event_emitter import EventEmitter
from opik_optimizer_framework.experiment_executor import run_experiment
from opik_optimizer_framework.result_aggregator import record_trial
from opik_optimizer_framework.types import (
    CandidateConfig,
    OptimizationState,
    TrialResult,
)

logger = logging.getLogger(__name__)


class EvaluationAdapter:
    """Optimizer-facing boundary for evaluating candidates.

    Accepts a CandidateConfig + dataset item IDs, runs validation,
    materialization, experiment execution, and result aggregation.
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

    def evaluate(
        self,
        config: CandidateConfig,
        dataset_item_ids: list[str],
        step_index: int,
        parent_candidate_ids: list[str] | None = None,
    ) -> TrialResult | None:
        """Evaluate a single candidate. Returns TrialResult or None if rejected."""
        valid, reason = validate_candidate(config, self._state)
        if not valid:
            logger.warning("Candidate rejected: %s", reason)
            return None

        candidate = materialize_candidate(
            config,
            step_index=step_index,
            parent_candidate_ids=parent_candidate_ids,
        )

        trial = run_experiment(
            client=self._client,
            candidate=candidate,
            dataset_name=self._dataset_name,
            dataset_item_ids=dataset_item_ids,
            optimization_id=self._optimization_id,
            metric_type=self._metric_type,
            metric_parameters=self._metric_parameters,
        )

        previous_best = self._state.best_trial
        record_trial(self._state, trial)
        self._event_emitter.on_trial_completed(trial)

        if self._state.best_trial is trial and self._state.best_trial is not previous_best:
            self._event_emitter.on_best_candidate_changed(trial)

        return trial

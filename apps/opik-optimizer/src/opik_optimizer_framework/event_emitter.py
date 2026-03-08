from __future__ import annotations

import logging

from opik_optimizer_framework.types import TrialResult

logger = logging.getLogger(__name__)


class EventEmitter:
    """Emits optimization lifecycle events.

    Currently logs only. Will emit to the frontend when connected.
    """

    def __init__(self, optimization_id: str | None = None) -> None:
        self._optimization_id = optimization_id

    def on_step_started(self, step_index: int) -> None:
        logger.info(
            "Step %d started (optimization=%s)",
            step_index,
            self._optimization_id,
        )

    def on_trial_completed(self, trial: TrialResult) -> None:
        logger.info(
            "Trial completed: candidate=%s step=%d score=%.4f parents=%s (optimization=%s)",
            trial.candidate_id,
            trial.step_index,
            trial.score,
            trial.parent_candidate_ids or [],
            self._optimization_id,
        )

    def on_best_candidate_changed(self, trial: TrialResult) -> None:
        logger.info(
            "New best candidate: %s step=%d score=%.4f (optimization=%s)",
            trial.candidate_id,
            trial.step_index,
            trial.score,
            self._optimization_id,
        )

    def on_progress(self, step_index: int, trials_completed: int, total_trials: int) -> None:
        logger.info(
            "Progress: step=%d trials=%d/%d (optimization=%s)",
            step_index,
            trials_completed,
            total_trials,
            self._optimization_id,
        )

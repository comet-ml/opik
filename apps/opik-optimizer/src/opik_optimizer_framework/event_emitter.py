from __future__ import annotations

import logging
from typing import Protocol

from opik_optimizer_framework.types import TrialResult

logger = logging.getLogger(__name__)


class EventEmitter(Protocol):
    def on_step_started(self, step_index: int, total_steps: int) -> None: ...
    def on_trial_completed(self, trial: TrialResult) -> None: ...
    def on_best_candidate_changed(self, trial: TrialResult) -> None: ...
    def on_progress(self, step_index: int, trials_completed: int, total_trials: int) -> None: ...


class SdkEventEmitter:
    """Updates optimization metadata via the Opik SDK."""

    def __init__(self, client: object, optimization_id: str) -> None:
        self._client = client
        self._optimization_id = optimization_id

    def on_step_started(self, step_index: int, total_steps: int) -> None:
        logger.info(
            "Step %d/%d started for optimization %s",
            step_index + 1,
            total_steps,
            self._optimization_id,
        )

    def on_trial_completed(self, trial: TrialResult) -> None:
        logger.info(
            "Trial completed: candidate=%s score=%.4f optimization=%s",
            trial.candidate_id,
            trial.score,
            self._optimization_id,
        )

    def on_best_candidate_changed(self, trial: TrialResult) -> None:
        logger.info(
            "New best candidate: %s score=%.4f optimization=%s",
            trial.candidate_id,
            trial.score,
            self._optimization_id,
        )

    def on_progress(self, step_index: int, trials_completed: int, total_trials: int) -> None:
        logger.info(
            "Progress: step=%d trials=%d/%d optimization=%s",
            step_index,
            trials_completed,
            total_trials,
            self._optimization_id,
        )


class LoggingEventEmitter:
    """Simple logging-only event emitter for testing and local development."""

    def on_step_started(self, step_index: int, total_steps: int) -> None:
        logger.info("Step %d/%d started", step_index + 1, total_steps)

    def on_trial_completed(self, trial: TrialResult) -> None:
        logger.info("Trial completed: candidate=%s score=%.4f", trial.candidate_id, trial.score)

    def on_best_candidate_changed(self, trial: TrialResult) -> None:
        logger.info("New best: candidate=%s score=%.4f", trial.candidate_id, trial.score)

    def on_progress(self, step_index: int, trials_completed: int, total_trials: int) -> None:
        logger.info("Progress: step=%d trials=%d/%d", step_index, trials_completed, total_trials)

from unittest.mock import MagicMock

from opik_optimizer_framework.event_emitter import EventEmitter
from opik_optimizer_framework.types import TrialResult


def _make_trial() -> TrialResult:
    return TrialResult(
        candidate_id="c1",
        step_index=0,
        score=0.85,
        metric_scores={"accuracy": 0.85},
        experiment_id="exp-1",
        experiment_name="trial-1",
        prompt_messages=[{"role": "user", "content": "test"}],
    )


class TestEventEmitter:
    def test_with_optimization_id(self):
        emitter = EventEmitter(optimization_id="opt-123")
        trial = _make_trial()
        emitter.on_step_started(0)
        emitter.on_trial_completed(trial)
        emitter.on_best_candidate_changed(trial)
        emitter.on_progress(step_index=0, trials_completed=3, total_trials=5)

    def test_without_optimization_id(self):
        emitter = EventEmitter()
        trial = _make_trial()
        emitter.on_step_started(0)
        emitter.on_trial_completed(trial)
        emitter.on_best_candidate_changed(trial)
        emitter.on_progress(0, 1, 5)


class TestMockEmitter:
    """Verify a MagicMock can stand in for EventEmitter."""

    def test_mock_emitter(self):
        mock_emitter = MagicMock()
        mock_emitter.on_step_started(0)
        mock_emitter.on_trial_completed(_make_trial())
        mock_emitter.on_best_candidate_changed(_make_trial())
        mock_emitter.on_progress(0, 1, 5)

        mock_emitter.on_step_started.assert_called_once_with(0)
        mock_emitter.on_trial_completed.assert_called_once()
        mock_emitter.on_best_candidate_changed.assert_called_once()
        mock_emitter.on_progress.assert_called_once_with(0, 1, 5)

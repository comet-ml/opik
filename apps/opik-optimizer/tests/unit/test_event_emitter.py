from unittest.mock import MagicMock

from opik_optimizer_framework.event_emitter import LoggingEventEmitter, SdkEventEmitter
from opik_optimizer_framework.types import TrialResult


def _make_trial() -> TrialResult:
    return TrialResult(
        candidate_id="c1",
        step_index=0,
        score=0.85,
        metric_scores={"accuracy": 0.85},
        experiment_id="exp-1",
        experiment_name="trial-1",
        config_hash="abc123",
        prompt_messages=[{"role": "user", "content": "test"}],
    )


class TestSdkEventEmitter:
    def test_on_step_started(self):
        client = MagicMock()
        emitter = SdkEventEmitter(client, "opt-123")
        emitter.on_step_started(0, 2)

    def test_on_trial_completed(self):
        client = MagicMock()
        emitter = SdkEventEmitter(client, "opt-123")
        emitter.on_trial_completed(_make_trial())

    def test_on_best_candidate_changed(self):
        client = MagicMock()
        emitter = SdkEventEmitter(client, "opt-123")
        emitter.on_best_candidate_changed(_make_trial())

    def test_on_progress(self):
        client = MagicMock()
        emitter = SdkEventEmitter(client, "opt-123")
        emitter.on_progress(step_index=0, trials_completed=3, total_trials=5)


class TestLoggingEventEmitter:
    def test_all_events(self):
        emitter = LoggingEventEmitter()
        trial = _make_trial()
        emitter.on_step_started(0, 2)
        emitter.on_trial_completed(trial)
        emitter.on_best_candidate_changed(trial)
        emitter.on_progress(0, 1, 5)


class TestEventEmitterProtocol:
    """Verify custom implementations satisfy the protocol."""

    def test_mock_emitter_as_protocol(self):
        mock_emitter = MagicMock()
        mock_emitter.on_step_started(0, 2)
        mock_emitter.on_trial_completed(_make_trial())
        mock_emitter.on_best_candidate_changed(_make_trial())
        mock_emitter.on_progress(0, 1, 5)

        mock_emitter.on_step_started.assert_called_once_with(0, 2)
        mock_emitter.on_trial_completed.assert_called_once()
        mock_emitter.on_best_candidate_changed.assert_called_once()
        mock_emitter.on_progress.assert_called_once_with(0, 1, 5)

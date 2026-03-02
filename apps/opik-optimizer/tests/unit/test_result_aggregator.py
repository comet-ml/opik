from opik_optimizer_framework.result_aggregator import (
    get_best,
    get_sorted_trials,
    record_trial,
)
from opik_optimizer_framework.types import OptimizationState, TrialResult


def _make_trial(candidate_id: str, score: float) -> TrialResult:
    return TrialResult(
        candidate_id=candidate_id,
        step_index=0,
        score=score,
        metric_scores={"accuracy": score},
        experiment_id=None,
        experiment_name=None,
        prompt_messages=[{"role": "user", "content": "test"}],
    )


class TestResultAggregator:
    def test_record_trial_adds_to_state(self):
        state = OptimizationState()
        trial = _make_trial("c1", 0.8)
        record_trial(state, trial)
        assert len(state.trials) == 1
        assert state.trials[0] is trial

    def test_record_trial_updates_best(self):
        state = OptimizationState()
        t1 = _make_trial("c1", 0.5)
        t2 = _make_trial("c2", 0.9)
        t3 = _make_trial("c3", 0.7)

        record_trial(state, t1)
        assert state.best_trial is t1

        record_trial(state, t2)
        assert state.best_trial is t2

        record_trial(state, t3)
        assert state.best_trial is t2

    def test_get_best_empty_state(self):
        state = OptimizationState()
        assert get_best(state) is None

    def test_get_best_returns_highest(self):
        state = OptimizationState()
        record_trial(state, _make_trial("c1", 0.3))
        record_trial(state, _make_trial("c2", 0.9))
        record_trial(state, _make_trial("c3", 0.6))
        assert get_best(state).candidate_id == "c2"

    def test_get_sorted_trials(self):
        state = OptimizationState()
        record_trial(state, _make_trial("c1", 0.3))
        record_trial(state, _make_trial("c2", 0.9))
        record_trial(state, _make_trial("c3", 0.6))

        sorted_trials = get_sorted_trials(state)
        assert [t.candidate_id for t in sorted_trials] == ["c2", "c3", "c1"]

    def test_sorted_trials_empty(self):
        state = OptimizationState()
        assert get_sorted_trials(state) == []

import json
from unittest.mock import MagicMock, patch

from opik_optimizer_framework.event_emitter import LoggingEventEmitter
from opik_optimizer_framework.optimizer.simple_optimizer import SimpleOptimizer
from opik_optimizer_framework.types import (
    CandidateConfig,
    OptimizationState,
    TrialResult,
)


def _make_trial(candidate_id: str, score: float, step_index: int = 0) -> TrialResult:
    return TrialResult(
        candidate_id=candidate_id,
        step_index=step_index,
        score=score,
        metric_scores={"accuracy": score},
        experiment_id=f"exp-{candidate_id}",
        experiment_name=f"trial-{candidate_id}",
        config_hash=f"hash-{candidate_id}",
        prompt_messages=[{"role": "user", "content": f"prompt-{candidate_id}"}],
    )


class TestSimpleOptimizer:
    def test_produces_correct_candidate_counts(self, sample_optimization_context):
        optimizer = SimpleOptimizer()
        state = OptimizationState()
        emitter = LoggingEventEmitter()

        call_count = {"step0": 0, "step1": 0}

        def mock_evaluate(config, dataset_item_ids, step_index, parent_candidate_ids=None):
            key = f"step{step_index}"
            call_count[key] += 1
            trial = _make_trial(f"c-{step_index}-{call_count[key]}", 0.5 + call_count[key] * 0.1, step_index)
            # Simulate the adapter recording the trial
            state.trials.append(trial)
            state.seen_hashes.add(trial.config_hash)
            if state.best_trial is None or trial.score > state.best_trial.score:
                state.best_trial = trial
            return trial

        adapter = MagicMock()
        adapter.evaluate = MagicMock(side_effect=mock_evaluate)

        improved_messages = json.dumps([
            {"role": "system", "content": "You are improved."},
            {"role": "user", "content": "Do {text}"},
        ])

        mock_response = MagicMock()
        mock_response.choices = [MagicMock()]
        mock_response.choices[0].message.content = improved_messages

        with patch("opik_optimizer_framework.optimizer.simple_optimizer.litellm") as mock_litellm:
            mock_litellm.completion.return_value = mock_response
            optimizer.run(
                context=sample_optimization_context,
                training_set=["id-1", "id-2"],
                validation_set=["id-3"],
                evaluation_adapter=adapter,
                state=state,
                event_emitter=emitter,
            )

        assert call_count["step0"] == 3
        assert call_count["step1"] == 2
        assert len(state.trials) == 5
        assert state.best_trial is not None

    def test_step2_uses_best_from_step1(self, sample_optimization_context):
        optimizer = SimpleOptimizer()
        state = OptimizationState()
        emitter = LoggingEventEmitter()

        step1_best_id = None
        step2_parent_ids = []

        call_count = {"total": 0}

        def mock_evaluate(config, dataset_item_ids, step_index, parent_candidate_ids=None):
            nonlocal step1_best_id, step2_parent_ids
            call_count["total"] += 1
            score = 0.9 if call_count["total"] == 2 else 0.5
            trial = _make_trial(f"c-{call_count['total']}", score, step_index)

            state.trials.append(trial)
            state.seen_hashes.add(trial.config_hash)
            if state.best_trial is None or trial.score > state.best_trial.score:
                state.best_trial = trial

            if step_index == 0 and score == 0.9:
                step1_best_id = trial.candidate_id
            if step_index == 1:
                step2_parent_ids = parent_candidate_ids or []

            return trial

        adapter = MagicMock()
        adapter.evaluate = MagicMock(side_effect=mock_evaluate)

        improved_messages = json.dumps([
            {"role": "user", "content": "improved"},
        ])
        mock_response = MagicMock()
        mock_response.choices = [MagicMock()]
        mock_response.choices[0].message.content = improved_messages

        with patch("opik_optimizer_framework.optimizer.simple_optimizer.litellm") as mock_litellm:
            mock_litellm.completion.return_value = mock_response
            optimizer.run(
                context=sample_optimization_context,
                training_set=["id-1"],
                validation_set=["id-2"],
                evaluation_adapter=adapter,
                state=state,
                event_emitter=emitter,
            )

        assert step1_best_id is not None
        assert step2_parent_ids == [step1_best_id]

    def test_retries_on_dedup_rejection(self, sample_optimization_context):
        optimizer = SimpleOptimizer()
        state = OptimizationState()
        emitter = LoggingEventEmitter()

        call_count = {"total": 0}

        def mock_evaluate(config, dataset_item_ids, step_index, parent_candidate_ids=None):
            call_count["total"] += 1
            # Return None on first call (simulating rejection), then succeed
            if call_count["total"] == 1:
                return None
            trial = _make_trial(f"c-{call_count['total']}", 0.7, step_index)
            state.trials.append(trial)
            state.seen_hashes.add(trial.config_hash)
            if state.best_trial is None or trial.score > state.best_trial.score:
                state.best_trial = trial
            return trial

        adapter = MagicMock()
        adapter.evaluate = MagicMock(side_effect=mock_evaluate)

        improved_messages = json.dumps([{"role": "user", "content": "improved"}])
        mock_response = MagicMock()
        mock_response.choices = [MagicMock()]
        mock_response.choices[0].message.content = improved_messages

        with patch("opik_optimizer_framework.optimizer.simple_optimizer.litellm") as mock_litellm:
            mock_litellm.completion.return_value = mock_response
            optimizer.run(
                context=sample_optimization_context,
                training_set=["id-1"],
                validation_set=["id-2"],
                evaluation_adapter=adapter,
                state=state,
                event_emitter=emitter,
            )

        # Should have retried past the first rejection
        assert call_count["total"] > 1
        assert len(state.trials) > 0

    def test_handles_llm_failure_gracefully(self, sample_optimization_context):
        optimizer = SimpleOptimizer()
        state = OptimizationState()
        emitter = LoggingEventEmitter()

        adapter = MagicMock()
        adapter.evaluate = MagicMock(return_value=None)

        with patch("opik_optimizer_framework.optimizer.simple_optimizer.litellm") as mock_litellm:
            mock_litellm.completion.side_effect = Exception("LLM error")
            optimizer.run(
                context=sample_optimization_context,
                training_set=["id-1"],
                validation_set=["id-2"],
                evaluation_adapter=adapter,
                state=state,
                event_emitter=emitter,
            )

        assert state.trials == []

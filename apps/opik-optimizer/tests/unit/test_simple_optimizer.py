import json
from unittest.mock import MagicMock, patch

from opik_optimizer_framework.optimizers.simple_optimizer import SimpleOptimizer
from opik_optimizer_framework.types import (
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
        config={"prompt_messages": [{"role": "user", "content": f"prompt-{candidate_id}"}]},
    )


class TestSimpleOptimizer:
    def test_produces_correct_candidate_counts(self, sample_optimization_context):
        optimizer = SimpleOptimizer()
        state = OptimizationState()

        call_count = {"total": 0}

        def mock_evaluate(config, dataset_item_ids, parent_candidate_ids=None):
            call_count["total"] += 1
            trial = _make_trial(f"c-{call_count['total']}", 0.5 + call_count["total"] * 0.1)
            state.trials.append(trial)

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

        with patch("opik_optimizer_framework.optimizers.simple_optimizer.litellm") as mock_litellm:
            mock_litellm.completion.return_value = mock_response
            optimizer.run(
                context=sample_optimization_context,
                training_set=[{"id": "id-1"}, {"id": "id-2"}],
                validation_set=[{"id": "id-3"}],
                evaluation_adapter=adapter,
                state=state,
            )

        assert call_count["total"] == 5  # 3 from step1 + 2 from step2
        assert len(state.trials) == 5
        assert state.best_trial is not None

    def test_step2_uses_best_from_step1(self, sample_optimization_context):
        optimizer = SimpleOptimizer()
        state = OptimizationState()

        best_after_step1_id = None
        step2_parent_ids = []

        call_count = {"total": 0}

        def mock_evaluate(config, dataset_item_ids, parent_candidate_ids=None):
            nonlocal best_after_step1_id, step2_parent_ids
            call_count["total"] += 1
            score = 0.9 if call_count["total"] == 2 else 0.5
            trial = _make_trial(f"c-{call_count['total']}", score)

            state.trials.append(trial)

            if state.best_trial is None or trial.score > state.best_trial.score:
                state.best_trial = trial

            # After step 1 (first 3 calls), record best
            if call_count["total"] == 3:
                best_after_step1_id = state.best_trial.candidate_id
            # Step 2 calls have parent_candidate_ids set
            if parent_candidate_ids:
                step2_parent_ids = parent_candidate_ids

            return trial

        adapter = MagicMock()
        adapter.evaluate = MagicMock(side_effect=mock_evaluate)

        improved_messages = json.dumps([
            {"role": "user", "content": "improved"},
        ])
        mock_response = MagicMock()
        mock_response.choices = [MagicMock()]
        mock_response.choices[0].message.content = improved_messages

        with patch("opik_optimizer_framework.optimizers.simple_optimizer.litellm") as mock_litellm:
            mock_litellm.completion.return_value = mock_response
            optimizer.run(
                context=sample_optimization_context,
                training_set=[{"id": "id-1"}],
                validation_set=[{"id": "id-2"}],
                evaluation_adapter=adapter,
                state=state,
            )

        assert best_after_step1_id is not None
        assert step2_parent_ids == [best_after_step1_id]

    def test_retries_on_dedup_rejection(self, sample_optimization_context):
        optimizer = SimpleOptimizer()
        state = OptimizationState()

        call_count = {"total": 0}

        def mock_evaluate(config, dataset_item_ids, parent_candidate_ids=None):
            call_count["total"] += 1
            if call_count["total"] == 1:
                return None
            trial = _make_trial(f"c-{call_count['total']}", 0.7)
            state.trials.append(trial)

            if state.best_trial is None or trial.score > state.best_trial.score:
                state.best_trial = trial
            return trial

        adapter = MagicMock()
        adapter.evaluate = MagicMock(side_effect=mock_evaluate)

        improved_messages = json.dumps([{"role": "user", "content": "improved"}])
        mock_response = MagicMock()
        mock_response.choices = [MagicMock()]
        mock_response.choices[0].message.content = improved_messages

        with patch("opik_optimizer_framework.optimizers.simple_optimizer.litellm") as mock_litellm:
            mock_litellm.completion.return_value = mock_response
            optimizer.run(
                context=sample_optimization_context,
                training_set=[{"id": "id-1"}],
                validation_set=[{"id": "id-2"}],
                evaluation_adapter=adapter,
                state=state,
            )

        # Should have retried past the first rejection
        assert call_count["total"] > 1
        assert len(state.trials) > 0

    def test_handles_llm_failure_gracefully(self, sample_optimization_context):
        optimizer = SimpleOptimizer()
        state = OptimizationState()

        adapter = MagicMock()
        adapter.evaluate = MagicMock(return_value=None)

        with patch("opik_optimizer_framework.optimizers.simple_optimizer.litellm") as mock_litellm:
            mock_litellm.completion.side_effect = Exception("LLM error")
            optimizer.run(
                context=sample_optimization_context,
                training_set=[{"id": "id-1"}],
                validation_set=[{"id": "id-2"}],
                evaluation_adapter=adapter,
                state=state,
            )

        assert state.trials == []

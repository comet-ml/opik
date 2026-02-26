import json
from unittest.mock import MagicMock, patch

import pytest

from opik_optimizer_framework.orchestrator import run_optimization
from opik_optimizer_framework.types import OptimizationContext, TrialResult


def _make_trial(candidate_id: str, score: float) -> TrialResult:
    return TrialResult(
        candidate_id=candidate_id,
        step_index=0,
        score=score,
        metric_scores={"accuracy": score},
        experiment_id=f"exp-{candidate_id}",
        experiment_name=f"trial-{candidate_id}",
        config_hash=f"hash-{candidate_id}",
        prompt_messages=[{"role": "user", "content": "test"}],
    )


class TestOrchestrator:
    def test_full_lifecycle_with_mocked_deps(self, sample_optimization_context):
        item_ids = [f"item-{i}" for i in range(10)]
        client = MagicMock()

        call_idx = {"n": 0}

        def make_llm_response(*args, **kwargs):
            call_idx["n"] += 1
            messages = json.dumps([{"role": "user", "content": f"improved v{call_idx['n']}"}])
            resp = MagicMock()
            resp.choices = [MagicMock()]
            resp.choices[0].message.content = messages
            return resp

        mock_score_result = MagicMock()
        mock_score_result.value = 0.8
        mock_score_result.scoring_failed = False
        mock_test_result = MagicMock()
        mock_test_result.score_results = [mock_score_result]

        mock_experiment_result = MagicMock()
        mock_experiment_result.test_results = [mock_test_result]
        mock_experiment_result.experiment_id = "exp-123"
        mock_experiment_result.experiment_name = "trial-1"

        with (
            patch("opik_optimizer_framework.optimizer.stupid_optimizer.litellm") as mock_litellm,
            patch("opik_optimizer_framework.experiment_execution.litellm"),
            patch("opik_optimizer_framework.experiment_execution.opik") as mock_opik,
            patch("opik_optimizer_framework.experiment_execution.evaluate_optimization_suite_trial") as mock_eval_suite,
        ):
            mock_litellm.completion.side_effect = make_llm_response
            mock_opik.Opik = type(client)
            mock_eval_suite.return_value = mock_experiment_result

            client.get_dataset.return_value = MagicMock()

            result = run_optimization(
                context=sample_optimization_context,
                client=client,
                dataset_item_ids=item_ids,
            )

        assert result is not None
        assert result.best_trial is not None
        assert result.score > 0
        assert len(result.all_trials) == 5

    def test_unknown_optimizer_raises(self):
        context = OptimizationContext(
            optimization_id="opt-1",
            dataset_name="ds",
            prompt_messages=[{"role": "user", "content": "hi"}],
            model="gpt-4",
            model_parameters={},
            metric_type="equals",
            metric_parameters={},
            optimizer_type="nonexistent",
            optimizer_parameters={},
        )
        with pytest.raises(ValueError, match="Unknown optimizer type"):
            run_optimization(
                context=context,
                client=MagicMock(),
                dataset_item_ids=[f"item-{i}" for i in range(10)],
            )

    def test_error_propagation(self, sample_optimization_context):
        item_ids = [f"item-{i}" for i in range(10)]
        client = MagicMock()

        with (
            patch("opik_optimizer_framework.optimizer.stupid_optimizer.litellm") as mock_litellm,
            patch("opik_optimizer_framework.experiment_execution.litellm"),
            patch("opik_optimizer_framework.experiment_execution.opik") as mock_opik,
            patch("opik_optimizer_framework.experiment_execution.evaluate_optimization_suite_trial"),
        ):
            mock_litellm.completion.side_effect = RuntimeError("LLM is down")
            mock_opik.Opik = type(client)

            # Should not raise - the stupid optimizer handles LLM failures gracefully
            result = run_optimization(
                context=sample_optimization_context,
                client=client,
                dataset_item_ids=item_ids,
            )
            assert result.score == 0.0

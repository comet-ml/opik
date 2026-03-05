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
        config={"prompt_messages": [{"role": "user", "content": "test"}]},
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

        mock_test_case = MagicMock()
        mock_test_case.dataset_item_id = "item-1"
        mock_test_case.dataset_item = None

        mock_score_result = MagicMock()
        mock_score_result.value = 1.0

        mock_test_result = MagicMock()
        mock_test_result.test_case = mock_test_case
        mock_test_result.score_results = [mock_score_result]
        mock_test_result.trial_id = "trial-1"

        mock_experiment_result = MagicMock()
        mock_experiment_result.test_results = [mock_test_result]
        mock_experiment_result.experiment_id = "exp-123"
        mock_experiment_result.experiment_name = "trial-1"

        with (
            patch("opik_optimizer_framework.optimizers.simple_optimizer.litellm") as mock_litellm,
            patch("opik_optimizer_framework.tasks.litellm"),
            patch("opik_optimizer_framework.experiment_executor.opik") as mock_opik,
            patch("opik_optimizer_framework.experiment_executor.evaluate_optimization_suite_trial") as mock_eval_suite,
        ):
            mock_litellm.completion.side_effect = make_llm_response
            mock_opik.Opik = type(client)
            mock_eval_suite.return_value = mock_experiment_result

            client.get_dataset.return_value = MagicMock()

            result = run_optimization(
                context=sample_optimization_context,
                client=client,
                dataset_items=[{"id": id} for id in item_ids],
            )

        assert result is not None
        assert result.best_trial is not None
        assert result.score > 0
        assert result.initial_score is not None
        assert len(result.all_trials) == 6  # 1 baseline + 3 step1 + 2 step2

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
            optimizable_keys=["prompt_messages"],
            baseline_config={"prompt_messages": [{"role": "user", "content": "hi"}], "model": "gpt-4"},
        )
        with pytest.raises(ValueError, match="Unknown optimizer type"):
            run_optimization(
                context=context,
                client=MagicMock(),
                dataset_items=[{"id": f"item-{i}"} for i in range(10)],
            )

    def test_small_dataset(self):
        """Verify the pipeline works with the minimum dataset size (2 items)."""
        context = OptimizationContext(
            optimization_id="small-test",
            dataset_name="tiny-dataset",
            prompt_messages=[{"role": "user", "content": "Hi {name}"}],
            model="openai/gpt-4o-mini",
            model_parameters={},
            metric_type="equals",
            metric_parameters={},
            optimizer_type="SimpleOptimizer",
            optimizer_parameters={},
            optimizable_keys=["prompt_messages"],
            baseline_config={"prompt_messages": [{"role": "user", "content": "Hi {name}"}], "model": "openai/gpt-4o-mini"},
        )

        improved = json.dumps([{"role": "user", "content": "Hello {name}"}])
        mock_resp = MagicMock()
        mock_resp.choices = [MagicMock()]
        mock_resp.choices[0].message.content = improved

        # Build 4 items, 3 passing -> pass_rate = 0.75
        test_results = []
        for i in range(4):
            tc = MagicMock()
            tc.dataset_item_id = f"item-{i}"
            tc.dataset_item = None
            sr = MagicMock()
            sr.value = 1.0 if i < 3 else 0.0
            tr = MagicMock()
            tr.test_case = tc
            tr.score_results = [sr]
            tr.trial_id = "trial-1"
            test_results.append(tr)

        mock_eval = MagicMock()
        mock_eval.test_results = test_results
        mock_eval.experiment_id = "exp-1"
        mock_eval.experiment_name = "trial-1"

        client = MagicMock()
        client.get_dataset.return_value = MagicMock()

        with (
            patch("opik_optimizer_framework.optimizers.simple_optimizer.litellm") as mock_litellm,
            patch("opik_optimizer_framework.tasks.litellm"),
            patch("opik_optimizer_framework.experiment_executor.opik") as mock_opik,
            patch("opik_optimizer_framework.experiment_executor.evaluate_optimization_suite_trial") as mock_eval_suite,
        ):
            mock_litellm.completion.return_value = mock_resp
            mock_opik.Opik = type(client)
            mock_eval_suite.return_value = mock_eval

            result = run_optimization(
                context=context,
                client=client,
                dataset_items=[{"id": "item-a"}, {"id": "item-b"}],
                seed=1,
            )

        assert result.best_trial is not None
        assert result.initial_score == 0.75
        assert result.score == 0.75

    def test_error_propagation(self, sample_optimization_context):
        item_ids = [f"item-{i}" for i in range(10)]
        client = MagicMock()

        mock_test_case = MagicMock()
        mock_test_case.dataset_item_id = "item-1"
        mock_test_case.dataset_item = None

        mock_score_result = MagicMock()
        mock_score_result.value = 1.0

        mock_test_result = MagicMock()
        mock_test_result.test_case = mock_test_case
        mock_test_result.score_results = [mock_score_result]
        mock_test_result.trial_id = "trial-baseline"

        mock_experiment_result = MagicMock()
        mock_experiment_result.test_results = [mock_test_result]
        mock_experiment_result.experiment_id = "exp-baseline"
        mock_experiment_result.experiment_name = "baseline"

        with (
            patch("opik_optimizer_framework.optimizers.simple_optimizer.litellm") as mock_litellm,
            patch("opik_optimizer_framework.tasks.litellm"),
            patch("opik_optimizer_framework.experiment_executor.opik") as mock_opik,
            patch("opik_optimizer_framework.experiment_executor.evaluate_optimization_suite_trial") as mock_eval_suite,
        ):
            mock_litellm.completion.side_effect = RuntimeError("LLM is down")
            mock_opik.Opik = type(client)
            mock_eval_suite.return_value = mock_experiment_result
            client.get_dataset.return_value = MagicMock()

            # Should not raise - the simple optimizer handles LLM failures gracefully
            result = run_optimization(
                context=sample_optimization_context,
                client=client,
                dataset_items=[{"id": id} for id in item_ids],
            )
            # Only the baseline trial succeeds; optimizer produces nothing
            assert result.initial_score == 1.0
            assert result.score == 1.0
            assert len(result.all_trials) == 1

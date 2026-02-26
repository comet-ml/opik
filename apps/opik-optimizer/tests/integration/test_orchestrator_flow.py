"""Integration test: end-to-end optimization flow with mocked SDK and litellm."""

import json
from unittest.mock import MagicMock, patch

from opik_optimizer_framework import OptimizationContext, run_optimization


class TestOrchestratorFlow:
    def test_end_to_end_flow(self):
        """Full pipeline: orchestrator → sampler → optimizer → evaluation → result."""
        context = OptimizationContext(
            optimization_id="integration-test-001",
            dataset_name="test-dataset",
            prompt_messages=[
                {"role": "system", "content": "You are a summarizer."},
                {"role": "user", "content": "Summarize: {text}"},
            ],
            model="openai/gpt-4o-mini",
            model_parameters={"temperature": 0.7},
            metric_type="equals",
            metric_parameters={},
            optimizer_type="stupid",
            optimizer_parameters={},
        )

        item_ids = [f"item-{i:03d}" for i in range(20)]

        # Mock LLM to return improved prompts
        improved_prompts = [
            json.dumps([
                {"role": "system", "content": f"You are an expert summarizer v{i}."},
                {"role": "user", "content": "Please summarize the following: {text}"},
            ])
            for i in range(10)
        ]

        call_index = {"llm": 0, "eval": 0}

        def mock_litellm_completion(*args, **kwargs):
            idx = call_index["llm"] % len(improved_prompts)
            call_index["llm"] += 1
            mock_resp = MagicMock()
            mock_resp.choices = [MagicMock()]
            mock_resp.choices[0].message.content = improved_prompts[idx]
            return mock_resp

        def mock_evaluate_trial(*args, **kwargs):
            call_index["eval"] += 1
            score = min(0.5 + (call_index["eval"] * 0.05), 1.0)
            mock_score_result = MagicMock()
            mock_score_result.value = score
            mock_score_result.scoring_failed = False
            mock_test_result = MagicMock()
            mock_test_result.score_results = [mock_score_result]

            mock_result = MagicMock()
            mock_result.test_results = [mock_test_result]
            mock_result.experiment_id = f"exp-{call_index['eval']}"
            mock_result.experiment_name = f"trial-{call_index['eval']}"
            return mock_result

        client = MagicMock()
        client.get_dataset.return_value = MagicMock()

        with (
            patch("opik_optimizer_framework.optimizer.stupid_optimizer.litellm") as mock_litellm,
            patch("opik_optimizer_framework.experiment_execution.litellm"),
            patch("opik_optimizer_framework.experiment_execution.opik") as mock_opik,
            patch("opik_optimizer_framework.experiment_execution.evaluate_optimization_suite_trial") as mock_eval_suite,
        ):
            mock_litellm.completion.side_effect = mock_litellm_completion
            mock_opik.Opik = type(client)
            mock_eval_suite.side_effect = mock_evaluate_trial

            result = run_optimization(
                context=context,
                client=client,
                dataset_item_ids=item_ids,
                seed=42,
            )

        # Verify results
        assert result is not None
        assert result.best_trial is not None
        assert result.score > 0
        assert len(result.all_trials) == 5  # 3 from step 1 + 2 from step 2

        # Verify trials have correct structure
        for trial in result.all_trials:
            assert trial.candidate_id is not None
            assert trial.config_hash is not None
            assert trial.score > 0
            assert len(trial.prompt_messages) > 0

        # Step 1 trials should be step_index 0, step 2 trials should be step_index 1
        step0_trials = [t for t in result.all_trials if t.step_index == 0]
        step1_trials = [t for t in result.all_trials if t.step_index == 1]
        assert len(step0_trials) == 3
        assert len(step1_trials) == 2

        # Best trial should have highest score
        all_scores = [t.score for t in result.all_trials]
        assert result.best_trial.score == max(all_scores)

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
            optimizer_type="stupid",
            optimizer_parameters={},
        )

        improved = json.dumps([{"role": "user", "content": "Hello {name}"}])
        mock_resp = MagicMock()
        mock_resp.choices = [MagicMock()]
        mock_resp.choices[0].message.content = improved

        mock_score_result = MagicMock()
        mock_score_result.value = 0.75
        mock_score_result.scoring_failed = False
        mock_test_result = MagicMock()
        mock_test_result.score_results = [mock_score_result]

        mock_eval = MagicMock()
        mock_eval.test_results = [mock_test_result]
        mock_eval.experiment_id = "exp-1"
        mock_eval.experiment_name = "trial-1"

        client = MagicMock()
        client.get_dataset.return_value = MagicMock()

        with (
            patch("opik_optimizer_framework.optimizer.stupid_optimizer.litellm") as mock_litellm,
            patch("opik_optimizer_framework.experiment_execution.litellm"),
            patch("opik_optimizer_framework.experiment_execution.opik") as mock_opik,
            patch("opik_optimizer_framework.experiment_execution.evaluate_optimization_suite_trial") as mock_eval_suite,
        ):
            mock_litellm.completion.return_value = mock_resp
            mock_opik.Opik = type(client)
            mock_eval_suite.return_value = mock_eval

            result = run_optimization(
                context=context,
                client=client,
                dataset_item_ids=["item-a", "item-b"],
                seed=1,
            )

        assert result.best_trial is not None
        assert result.score == 0.75

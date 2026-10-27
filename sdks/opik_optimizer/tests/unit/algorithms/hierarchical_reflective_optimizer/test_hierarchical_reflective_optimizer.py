# mypy: disable-error-code=no-untyped-def

from collections.abc import Callable
from unittest.mock import MagicMock

import pytest
from opik.evaluation.evaluation_result import EvaluationResult

from opik_optimizer import (
    ChatPrompt,
    HierarchicalReflectiveOptimizer,
    OptimizationResult,
)
from opik_optimizer.algorithms.hierarchical_reflective_optimizer.types import (
    FailureMode,
    HierarchicalRootCauseAnalysis,
)
from tests.unit.fixtures import assert_baseline_early_stop
from tests.unit.fixtures import make_baseline_prompt, make_two_prompt_bundle


def _mock_experiment_result(score: float) -> MagicMock:
    # spec=EvaluationResult so isinstance(..., EvaluationResult) holds — the
    # baseline/candidate code paths gate on that type, and a bare MagicMock would
    # otherwise fall through to float() coercion (== 1.0) and trip perfect-score
    # early-stop. The score itself is supplied directly by the callers that need
    # it, so the objective name is left unmatched (baseline reads back 0.0 here).
    score_result = MagicMock(value=score)
    test_result = MagicMock(score_results=[score_result])
    return MagicMock(spec=EvaluationResult, test_results=[test_result])


def _stub_evaluation(optimizer, monkeypatch: pytest.MonkeyPatch, score: float) -> None:
    """Stub the scoring and root-cause LLM calls around the optimization loop."""
    analysis = HierarchicalRootCauseAnalysis(
        total_test_cases=1,
        num_batches=1,
        unified_failure_modes=[
            FailureMode(name="Failure", description="desc", root_cause="cause")
        ],
        synthesis_notes="Test",
    )
    monkeypatch.setattr(
        optimizer,
        "_hierarchical_root_cause_analysis",
        lambda evaluation_result, traces=None: analysis,
    )
    monkeypatch.setattr(
        optimizer,
        "evaluate_prompt",
        lambda **kwargs: _mock_experiment_result(score)
        if kwargs.get("return_evaluation_result")
        else score,
    )


class TestHierarchicalReflectiveOptimizerOptimizePrompt:
    @pytest.mark.parametrize(
        "use_bundle,expected_type", [(False, ChatPrompt), (True, dict)]
    )
    def test_result_prompt_matches_input_shape(
        self,
        mock_optimization_context,
        monkeypatch,
        simple_chat_prompt,
        mock_dataset,
        sample_dataset_items,
        sample_metric,
        use_bundle: bool,
        expected_type: type,
    ) -> None:
        mock_optimization_context()
        optimizer = HierarchicalReflectiveOptimizer(
            model="gpt-4o-mini", verbose=0, seed=42
        )
        _stub_evaluation(optimizer, monkeypatch, score=0.5)

        def mock_generate_and_evaluate(**kwargs):
            prompts = kwargs.get("best_prompts", {})
            improved = {name: p.copy() for name, p in prompts.items()}
            return improved, 0.85, _mock_experiment_result(0.85)

        monkeypatch.setattr(
            optimizer, "_generate_and_evaluate_improvement", mock_generate_and_evaluate
        )

        result = optimizer.optimize_prompt(
            prompt=make_two_prompt_bundle() if use_bundle else simple_chat_prompt,
            dataset=mock_dataset(
                sample_dataset_items, name="test-dataset", dataset_id="dataset-123"
            ),
            metric=sample_metric,
            max_trials=2,
            n_samples=2,
        )

        assert isinstance(result, OptimizationResult)
        assert isinstance(result.prompt, expected_type)


class TestHierarchicalReflectiveOptimizerEarlyStop:
    def test_skips_on_perfect_score(
        self,
        mock_opik_client: Callable[..., MagicMock],
        monkeypatch: pytest.MonkeyPatch,
        mock_dataset,
        sample_dataset_items,
        sample_metric,
    ) -> None:
        mock_opik_client()
        dataset = mock_dataset(
            sample_dataset_items, name="test-dataset", dataset_id="dataset-123"
        )
        optimizer = HierarchicalReflectiveOptimizer(model="gpt-4o", perfect_score=0.95)

        # Mock the base class's evaluate_prompt for baseline computation (returns float)
        monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **kwargs: 0.96)

        prompt = make_baseline_prompt()
        result = optimizer.optimize_prompt(
            prompt=prompt,
            dataset=dataset,
            metric=sample_metric,
            max_trials=1,
        )

        assert_baseline_early_stop(result, perfect_score=0.95)


class TestHierarchicalReflectiveOptimizerCounters:
    def test_respects_max_trials_and_records_history(
        self,
        mock_optimization_context,
        monkeypatch: pytest.MonkeyPatch,
        simple_chat_prompt,
        mock_dataset,
        sample_dataset_items,
        sample_metric,
    ) -> None:
        mock_optimization_context()
        dataset = mock_dataset(
            sample_dataset_items, name="test-dataset", dataset_id="dataset-123"
        )
        optimizer = HierarchicalReflectiveOptimizer(model="gpt-4o-mini", verbose=0)
        prompt = simple_chat_prompt

        _stub_evaluation(optimizer, monkeypatch, score=0.1)

        improvement_calls: list[int] = []

        def fake_generate_and_evaluate_improvement(**kwargs):
            improvement_calls.append(kwargs.get("attempt", 0))
            prompts_arg = kwargs.get("best_prompts", {})
            improved = {name: p.copy() for name, p in prompts_arg.items()}
            return improved, 0.2, _mock_experiment_result(0.2)

        monkeypatch.setattr(
            optimizer,
            "_generate_and_evaluate_improvement",
            fake_generate_and_evaluate_improvement,
        )

        result = optimizer.optimize_prompt(
            prompt=prompt,
            dataset=dataset,
            metric=sample_metric,
            max_trials=1,
        )

        assert len(improvement_calls) == 1
        assert result.details["trials_completed"] == 1
        assert len(result.history) == 1
        assert result.history[0]["trials_completed"] == 1


class TestHierarchicalReflectiveOptimizerStopping:
    def test_perfect_score_returns_winning_prompt(
        self,
        mock_optimization_context,
        monkeypatch: pytest.MonkeyPatch,
        simple_chat_prompt,
        mock_dataset,
        sample_dataset_items,
        sample_metric,
    ) -> None:
        mock_optimization_context()
        optimizer = HierarchicalReflectiveOptimizer(model="gpt-4o-mini", verbose=0)
        _stub_evaluation(optimizer, monkeypatch, score=0.1)
        winner = ChatPrompt(system="winning prompt", user="{question}")

        def perfect_improvement(**kwargs):
            return {"chat-prompt": winner}, 1.0, _mock_experiment_result(1.0)

        monkeypatch.setattr(
            optimizer, "_generate_and_evaluate_improvement", perfect_improvement
        )

        result = optimizer.optimize_prompt(
            prompt=simple_chat_prompt,
            dataset=mock_dataset(sample_dataset_items, name="ds", dataset_id="ds-1"),
            metric=sample_metric,
            max_trials=5,
        )

        assert result.score == 1.0
        assert result.prompt.get_messages() == winner.get_messages()
        assert result.details["finish_reason"] == "perfect_score"

    def test_reused_instance_runs_again_after_stop(
        self,
        mock_optimization_context,
        monkeypatch: pytest.MonkeyPatch,
        simple_chat_prompt,
        mock_dataset,
        sample_dataset_items,
        sample_metric,
    ) -> None:
        mock_optimization_context()
        optimizer = HierarchicalReflectiveOptimizer(model="gpt-4o-mini", verbose=0)
        _stub_evaluation(optimizer, monkeypatch, score=0.1)
        calls: list[int] = []

        def no_improvement(**kwargs):
            calls.append(kwargs["attempt"])
            prompts = kwargs["best_prompts"]
            return dict(prompts), 0.0, _mock_experiment_result(0.0)

        monkeypatch.setattr(
            optimizer, "_generate_and_evaluate_improvement", no_improvement
        )
        dataset = mock_dataset(sample_dataset_items, name="ds", dataset_id="ds-1")

        for _ in range(2):
            calls.clear()
            optimizer.optimize_prompt(
                prompt=simple_chat_prompt,
                dataset=dataset,
                metric=sample_metric,
                max_trials=5,
            )
            # Converges after one round of 3 attempts (max_retries=2) — both runs.
            assert calls == [1, 2, 3]

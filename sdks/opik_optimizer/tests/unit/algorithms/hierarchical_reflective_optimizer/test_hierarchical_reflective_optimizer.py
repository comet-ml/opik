# mypy: disable-error-code=no-untyped-def

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
from tests.unit.fixtures import make_two_prompt_bundle


def _mock_experiment_result(score: float) -> MagicMock:
    # spec=EvaluationResult so isinstance(..., EvaluationResult) holds — the
    # baseline/candidate code paths gate on that type, and a bare MagicMock would
    # otherwise fall through to float() coercion (== 1.0) and trip perfect-score
    # early-stop. The score itself is supplied directly by the callers that need
    # it, so the objective name is left unmatched (baseline reads back 0.0 here).
    score_result = MagicMock(value=score)
    test_result = MagicMock(score_results=[score_result])
    return MagicMock(spec=EvaluationResult, test_results=[test_result])


class TestHierarchicalReflectiveOptimizerOptimizePrompt:
    def test_single_prompt_returns_chat_prompt(
        self,
        mock_optimization_context,
        monkeypatch,
        simple_chat_prompt,
        mock_dataset,
        sample_dataset_items,
        sample_metric,
    ) -> None:
        mock_optimization_context()

        optimizer = HierarchicalReflectiveOptimizer(
            model="gpt-4o-mini", verbose=0, seed=42
        )
        prompt = simple_chat_prompt
        dataset = mock_dataset(
            sample_dataset_items, name="test-dataset", dataset_id="dataset-123"
        )

        eval_scores = iter([0.5, 0.6, 0.7, 0.85])

        def mock_evaluate(**kwargs):
            score = next(eval_scores, 0.85)
            if kwargs.get("return_evaluation_result"):
                return _mock_experiment_result(score)
            return score

        monkeypatch.setattr(optimizer, "evaluate_prompt", mock_evaluate)

        analysis = HierarchicalRootCauseAnalysis(
            total_test_cases=5,
            num_batches=1,
            unified_failure_modes=[
                FailureMode(
                    name="Test", description="Test failure", root_cause="Test cause"
                )
            ],
            synthesis_notes="Test",
        )
        monkeypatch.setattr(
            optimizer,
            "_hierarchical_root_cause_analysis",
            lambda evaluation_result: analysis,
        )

        def mock_generate_and_evaluate(**kwargs):
            prompts = kwargs.get("best_prompts", {})
            improved = {name: p.copy() for name, p in prompts.items()}
            return improved, 0.85, _mock_experiment_result(0.85)

        monkeypatch.setattr(
            optimizer, "_generate_and_evaluate_improvement", mock_generate_and_evaluate
        )

        result = optimizer.optimize_prompt(
            prompt=prompt,
            dataset=dataset,
            metric=sample_metric,
            max_trials=2,
            n_samples=2,
        )

        assert isinstance(result, OptimizationResult)
        assert isinstance(result.prompt, ChatPrompt)

    def test_dict_prompt_returns_dict(
        self,
        mock_optimization_context,
        monkeypatch,
        mock_dataset,
        sample_dataset_items,
        sample_metric,
    ) -> None:
        mock_optimization_context()

        optimizer = HierarchicalReflectiveOptimizer(
            model="gpt-4o-mini", verbose=0, seed=42
        )
        prompts = make_two_prompt_bundle()
        dataset = mock_dataset(
            sample_dataset_items, name="test-dataset", dataset_id="dataset-123"
        )

        eval_scores = iter([0.5, 0.6, 0.7, 0.85])

        def mock_evaluate(**kwargs):
            score = next(eval_scores, 0.85)
            if kwargs.get("return_evaluation_result"):
                return _mock_experiment_result(score)
            return score

        monkeypatch.setattr(optimizer, "evaluate_prompt", mock_evaluate)

        analysis = HierarchicalRootCauseAnalysis(
            total_test_cases=5,
            num_batches=1,
            unified_failure_modes=[
                FailureMode(
                    name="Test", description="Test failure", root_cause="Test cause"
                )
            ],
            synthesis_notes="Test",
        )
        monkeypatch.setattr(
            optimizer,
            "_hierarchical_root_cause_analysis",
            lambda evaluation_result: analysis,
        )

        def mock_generate_and_evaluate(**kwargs):
            prompts_arg = kwargs.get("best_prompts", {})
            improved = {name: p.copy() for name, p in prompts_arg.items()}
            return improved, 0.85, _mock_experiment_result(0.85)

        monkeypatch.setattr(
            optimizer, "_generate_and_evaluate_improvement", mock_generate_and_evaluate
        )

        result = optimizer.optimize_prompt(
            prompt=prompts,
            dataset=dataset,
            metric=sample_metric,
            max_trials=2,
            n_samples=2,
        )

        assert isinstance(result, OptimizationResult)
        assert isinstance(result.prompt, dict)


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

        monkeypatch.setattr(
            optimizer,
            "evaluate_prompt",
            lambda **kwargs: _mock_experiment_result(0.1)
            if kwargs.get("return_evaluation_result")
            else 0.1,
        )

        analysis = HierarchicalRootCauseAnalysis(
            total_test_cases=1,
            num_batches=1,
            unified_failure_modes=[
                FailureMode(
                    name="Failure",
                    description="desc",
                    root_cause="cause",
                )
            ],
            synthesis_notes="Test",
        )
        monkeypatch.setattr(
            optimizer,
            "_hierarchical_root_cause_analysis",
            lambda evaluation_result: analysis,
        )

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

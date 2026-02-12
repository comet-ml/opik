"""Pytest fixtures for mocking evaluation outputs/results."""

from __future__ import annotations

from collections.abc import Callable
from typing import Any
from unittest.mock import MagicMock

import pytest


@pytest.fixture
def mock_evaluation_result() -> Callable[..., MagicMock]:
    """Factory for creating mock EvaluationResult-shaped objects."""

    def _create(
        scores: list[float],
        *,
        reasons: list[str] | None = None,
        dataset_item_ids: list[str] | None = None,
    ) -> MagicMock:
        mock_result = MagicMock()
        test_results: list[MagicMock] = []

        for i, score in enumerate(scores):
            test_result = MagicMock()
            test_case = MagicMock()
            test_case.dataset_item_id = (
                dataset_item_ids[i] if dataset_item_ids else f"item-{i}"
            )
            test_result.test_case = test_case
            test_result.trial_id = f"trial-{i}"

            score_result = MagicMock()
            score_result.name = "accuracy"
            score_result.value = score
            score_result.reason = reasons[i] if reasons else None
            score_result.scoring_failed = False

            test_result.score_results = [score_result]
            test_results.append(test_result)

        mock_result.test_results = test_results
        return mock_result

    return _create


@pytest.fixture
def mock_task_evaluator(monkeypatch: pytest.MonkeyPatch) -> Callable[..., Any]:
    """
    Mock `opik_optimizer.core.evaluation.evaluate` to return configurable scores/results.
    """

    def _configure(
        score: float | None = None,
        *,
        scores: list[float] | None = None,
        return_evaluation_result: bool = False,
    ) -> Any:
        call_count: dict[str, int] = {"n": 0}
        captured_calls: list[dict[str, Any]] = []

        def fake_evaluate(
            dataset: Any,
            evaluated_task: Any,
            metric: Any,
            num_threads: Any,
            optimization_id: Any = None,
            dataset_item_ids: Any = None,
            project_name: Any = None,
            n_samples: Any = None,
            experiment_config: Any = None,
            verbose: Any = 1,
            return_evaluation_result: bool = False,
            **kwargs: Any,
        ) -> Any:
            _ = dataset_item_ids, project_name, experiment_config, verbose, kwargs
            captured_calls.append(
                {
                    "dataset": dataset,
                    "evaluated_task": evaluated_task,
                    "metric": metric,
                    "num_threads": num_threads,
                    "optimization_id": optimization_id,
                    "n_samples": n_samples,
                    "return_evaluation_result": return_evaluation_result,
                }
            )

            if scores is not None:
                idx = min(call_count["n"], len(scores) - 1)
                current_score = scores[idx]
            else:
                current_score = score if score is not None else 0.5

            call_count["n"] += 1

            if return_evaluation_result:
                mock_result = MagicMock()
                mock_result.test_results = []
                items = dataset.get_items() if hasattr(dataset, "get_items") else []
                for i, item in enumerate(items[:5]):
                    test_result = MagicMock()
                    test_case = MagicMock()
                    test_case.dataset_item_id = item.get("id", f"item-{i}")
                    test_result.test_case = test_case

                    score_result = MagicMock()
                    score_result.name = "test_metric"
                    score_result.value = current_score
                    score_result.reason = None
                    score_result.scoring_failed = False

                    test_result.score_results = [score_result]
                    mock_result.test_results.append(test_result)

                return mock_result

            return current_score

        def fake_evaluate_with_result(
            dataset: Any,
            evaluated_task: Any,
            metric: Any,
            num_threads: Any,
            optimization_id: Any = None,
            dataset_item_ids: Any = None,
            project_name: Any = None,
            n_samples: Any = None,
            experiment_config: Any = None,
            verbose: Any = 1,
            **kwargs: Any,
        ) -> tuple[float, Any]:
            _ = dataset_item_ids, project_name, experiment_config, verbose, kwargs
            captured_calls.append(
                {
                    "dataset": dataset,
                    "evaluated_task": evaluated_task,
                    "metric": metric,
                    "num_threads": num_threads,
                    "optimization_id": optimization_id,
                    "n_samples": n_samples,
                    "return_evaluation_result": True,
                }
            )

            if scores is not None:
                idx = min(call_count["n"], len(scores) - 1)
                current_score = scores[idx]
            else:
                current_score = score if score is not None else 0.5

            call_count["n"] += 1

            mock_result = MagicMock()
            mock_result.test_results = []
            items = dataset.get_items() if hasattr(dataset, "get_items") else []
            for i, item in enumerate(items[:5]):
                test_result = MagicMock()
                test_case = MagicMock()
                test_case.dataset_item_id = item.get("id", f"item-{i}")
                test_result.test_case = test_case

                score_result = MagicMock()
                score_result.name = getattr(metric, "__name__", "test_metric")
                score_result.value = current_score
                score_result.reason = None
                score_result.scoring_failed = False

                test_result.score_results = [score_result]
                mock_result.test_results.append(test_result)

            return current_score, mock_result

        monkeypatch.setattr("opik_optimizer.core.evaluation.evaluate", fake_evaluate)
        monkeypatch.setattr(
            "opik_optimizer.core.evaluation.evaluate_with_result",
            fake_evaluate_with_result,
        )

        class Evaluator:
            pass

        evaluator = Evaluator()
        evaluator.calls = captured_calls  # type: ignore[attr-defined]
        evaluator.call_count = call_count  # type: ignore[attr-defined]
        return evaluator

    return _configure

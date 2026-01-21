from __future__ import annotations

from typing import Any
from collections.abc import Callable

import pytest

from opik.evaluation.metrics.score_result import ScoreResult
from opik_optimizer.metrics.multi_metric_objective import MultiMetricObjective
from tests.unit.evaluation._multi_metric_test_helpers import make_constant_metric


metric_returning_0_5 = make_constant_metric("metric_returning_0_5", 0.5)
metric_returning_0_2 = make_constant_metric("metric_returning_0_2", 0.2)
metric_returning_0_1 = make_constant_metric("metric_returning_0_1", 0.1)


class TestMultiMetricObjectiveDefaults:
    def test_equal_weights_when_no_weights_provided__one_metric(self) -> None:
        metrics: list[Callable[[dict[str, Any], str], ScoreResult]] = [
            metric_returning_0_5
        ]
        multi_metric = MultiMetricObjective(metrics=metrics)

        result = multi_metric(dataset_item={}, llm_output="test output")

        assert multi_metric.weights == [1.0], (
            f"Expected weight [1.0], got {multi_metric.weights}"
        )

        expected_value = 0.5
        assert result.value == pytest.approx(expected_value), (
            f"Expected weighted score {expected_value}, got {result.value}"
        )

        raw_scores = result.metadata["raw_score_results"]
        assert len(raw_scores) == 1, "Should have exactly one raw score"
        assert raw_scores[0].name == "metric_returning_0_5"
        assert raw_scores[0].value == 0.5

    def test_equal_weights_when_no_weights_provided__two_metrics(self) -> None:
        metrics: list[Callable[[dict[str, Any], str], ScoreResult]] = [
            metric_returning_0_5,
            metric_returning_0_2,
        ]
        multi_metric = MultiMetricObjective(metrics=metrics)

        result = multi_metric(dataset_item={}, llm_output="test output")

        assert multi_metric.weights == [0.5, 0.5], (
            f"Expected equal weights [0.5, 0.5], got {multi_metric.weights}"
        )

        expected_value = 0.35
        assert result.value == pytest.approx(expected_value), (
            f"Expected weighted score {expected_value}, got {result.value}"
        )

    def test_equal_weights_when_no_weights_provided__three_metrics(self) -> None:
        metrics: list[Callable[[dict[str, Any], str], ScoreResult]] = [
            metric_returning_0_5,
            metric_returning_0_2,
            metric_returning_0_1,
        ]
        multi_metric = MultiMetricObjective(metrics=metrics)

        result = multi_metric(dataset_item={}, llm_output="test output")

        expected_weight = 1 / 3
        assert all(w == pytest.approx(expected_weight) for w in multi_metric.weights), (
            f"Expected equal weights [1/3, 1/3, 1/3], got {multi_metric.weights}"
        )

        expected_value = 0.8 / 3
        assert result.value == pytest.approx(expected_value), (
            f"Expected weighted score {expected_value}, got {result.value}"
        )


class TestMultiMetricObjectiveNamingAndReason:
    def test_custom_name_is_set(self) -> None:
        metrics: list[Callable[[dict[str, Any], str], ScoreResult]] = [
            metric_returning_0_5,
            metric_returning_0_2,
        ]
        custom_name = "my_custom_objective"
        multi_metric = MultiMetricObjective(metrics=metrics, name=custom_name)

        result = multi_metric(dataset_item={}, llm_output="test output")

        assert multi_metric.__name__ == custom_name, (
            f"Expected name '{custom_name}', got '{multi_metric.__name__}'"
        )
        assert result.name == custom_name, (
            f"Expected result name '{custom_name}', got '{result.name}'"
        )

    def test_default_name_is_set(self) -> None:
        metrics: list[Callable[[dict[str, Any], str], ScoreResult]] = [
            metric_returning_0_5,
            metric_returning_0_2,
        ]
        multi_metric = MultiMetricObjective(metrics=metrics)

        result = multi_metric(dataset_item={}, llm_output="test output")

        expected_name = "multi_metric_objective"
        assert multi_metric.__name__ == expected_name, (
            f"Expected default name '{expected_name}', got '{multi_metric.__name__}'"
        )
        assert result.name == expected_name, (
            f"Expected result name '{expected_name}', got '{result.name}'"
        )

    def test_default_reason_is_generated(self) -> None:
        metrics: list[Callable[[dict[str, Any], str], ScoreResult]] = [
            metric_returning_0_5,
            metric_returning_0_2,
        ]
        multi_metric = MultiMetricObjective(metrics=metrics)

        result = multi_metric(dataset_item={}, llm_output="test output")

        assert result.reason == (
            "metric_returning_0_5=0.500 (w=0.50) | metric_returning_0_2=0.200 (w=0.50)"
        )

    def test_reason_override_takes_precedence(self) -> None:
        metrics: list[Callable[[dict[str, Any], str], ScoreResult]] = [
            metric_returning_0_5,
            metric_returning_0_2,
        ]
        multi_metric = MultiMetricObjective(metrics=metrics, reason="static reason")

        result_static = multi_metric(dataset_item={}, llm_output="test output")
        result_override = multi_metric(
            dataset_item={}, llm_output="test output", reason="call reason"
        )

        assert result_static.reason == "static reason"
        assert result_override.reason == "call reason"

    def test_reason_builder_overrides_default(self) -> None:
        metrics: list[Callable[[dict[str, Any], str], ScoreResult]] = [
            metric_returning_0_5,
            metric_returning_0_2,
        ]

        def build_reason(
            score_results: list[ScoreResult],
            weights: list[float],
            total: float,
        ) -> str:
            return f"total={total:.2f}"

        multi_metric = MultiMetricObjective(
            metrics=metrics, reason_builder=build_reason
        )

        result = multi_metric(dataset_item={}, llm_output="test output")

        assert result.reason == "total=0.35"


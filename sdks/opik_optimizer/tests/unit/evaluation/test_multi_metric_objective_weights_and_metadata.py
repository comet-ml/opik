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


class TestMultiMetricObjectiveWeights:
    def test_weights_are_applied_correctly(self) -> None:
        """Test that custom weights are properly applied to metrics"""
        metrics: list[Callable[[dict[str, Any], str], ScoreResult]] = [
            metric_returning_0_5,
            metric_returning_0_2,
        ]
        weights = [0.5, 0.5]
        multi_metric = MultiMetricObjective(metrics=metrics, weights=weights)

        result = multi_metric(dataset_item={}, llm_output="test output")

        expected_value = 0.35
        assert result.value == pytest.approx(expected_value), (
            f"Expected weighted score {expected_value}, got {result.value}"
        )

    def test_final_score_is_weighted_average__three_metrics(self) -> None:
        metrics: list[Callable[[dict[str, Any], str], ScoreResult]] = [
            metric_returning_0_5,
            metric_returning_0_2,
            metric_returning_0_1,
        ]
        weights = [0.5, 0.3, 0.2]
        multi_metric = MultiMetricObjective(metrics=metrics, weights=weights)

        result = multi_metric(dataset_item={}, llm_output="test output")

        expected_value = 0.33
        assert result.value == pytest.approx(expected_value), (
            f"Expected weighted score {expected_value}, got {result.value}"
        )

    def test_weights_sum_not_required_to_be_one(self) -> None:
        metrics: list[Callable[[dict[str, Any], str], ScoreResult]] = [
            metric_returning_0_5,
            metric_returning_0_2,
        ]
        weights = [2.0, 3.0]
        multi_metric = MultiMetricObjective(metrics=metrics, weights=weights)

        result = multi_metric(dataset_item={}, llm_output="test output")

        expected_value = 1.6
        assert result.value == pytest.approx(expected_value), (
            f"Expected weighted score {expected_value}, got {result.value}"
        )

    def test_zero_weight_excludes_metric_from_result(self) -> None:
        metrics: list[Callable[[dict[str, Any], str], ScoreResult]] = [
            metric_returning_0_5,
            metric_returning_0_2,
            metric_returning_0_1,
        ]
        weights = [0.5, 0.0, 0.5]
        multi_metric = MultiMetricObjective(metrics=metrics, weights=weights)

        result = multi_metric(dataset_item={}, llm_output="test output")

        expected_value = 0.3
        assert result.value == pytest.approx(expected_value), (
            f"Expected weighted score {expected_value}, got {result.value}"
        )

        raw_scores = result.metadata["raw_score_results"]
        assert len(raw_scores) == 3, "All metrics should be in metadata"
        assert raw_scores[1].name == "metric_returning_0_2"


class TestMultiMetricObjectiveMetadata:
    def test_metadata_contains_raw_score_results(self) -> None:
        metrics: list[Callable[[dict[str, Any], str], ScoreResult]] = [
            metric_returning_0_5,
            metric_returning_0_2,
            metric_returning_0_1,
        ]
        weights = [0.4, 0.4, 0.2]
        multi_metric = MultiMetricObjective(metrics=metrics, weights=weights)

        result = multi_metric(dataset_item={}, llm_output="test output")

        assert "raw_score_results" in result.metadata, (
            "Metadata should contain 'raw_score_results' key"
        )

        raw_scores = result.metadata["raw_score_results"]
        assert len(raw_scores) == 3, (
            f"Expected 3 raw score results, got {len(raw_scores)}"
        )

        assert raw_scores[0].name == "metric_returning_0_5"
        assert raw_scores[0].value == 0.5
        assert raw_scores[1].name == "metric_returning_0_2"
        assert raw_scores[1].value == 0.2
        assert raw_scores[2].name == "metric_returning_0_1"
        assert raw_scores[2].value == 0.1

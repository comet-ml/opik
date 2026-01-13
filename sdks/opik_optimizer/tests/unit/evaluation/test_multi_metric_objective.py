import pytest
from typing import Any
from collections.abc import Callable

from opik.evaluation.metrics import base_metric
from opik.evaluation.metrics.score_result import ScoreResult
from opik_optimizer.multi_metric_objective import MultiMetricObjective


# Mock metric functions for testing
def metric_returning_0_5(dataset_item: dict[str, Any], llm_output: str) -> ScoreResult:
    """Mock metric that returns a score of 0.5"""
    return ScoreResult(name="metric_returning_0_5", value=0.5)


def metric_returning_0_2(dataset_item: dict[str, Any], llm_output: str) -> ScoreResult:
    """Mock metric that returns a score of 0.2"""
    return ScoreResult(name="metric_returning_0_2", value=0.2)


def metric_returning_0_1(dataset_item: dict[str, Any], llm_output: str) -> ScoreResult:
    """Mock metric that returns a score of 0.1"""
    return ScoreResult(name="metric_returning_0_1", value=0.1)


class TestMultiMetricObjective:
    def test_weights_are_applied_correctly(self) -> None:
        """Test that custom weights are properly applied to metrics"""
        # Arrange
        metrics: list[Callable[[dict[str, Any], str], ScoreResult]] = [
            metric_returning_0_5,
            metric_returning_0_2,
        ]
        weights = [0.5, 0.5]
        multi_metric = MultiMetricObjective(metrics=metrics, weights=weights)

        # Act
        result = multi_metric(dataset_item={}, llm_output="test output")

        # Assert
        # Expected: (0.5 * 0.5) + (0.2 * 0.5) = 0.25 + 0.1 = 0.35
        expected_value = 0.35
        assert result.value == pytest.approx(expected_value), (
            f"Expected weighted score {expected_value}, got {result.value}"
        )

    def test_final_score_is_weighted_average__three_metrics(self) -> None:
        """Test that the final score is correct weighted average with three metrics"""
        # Arrange
        metrics: list[Callable[[dict[str, Any], str], ScoreResult]] = [
            metric_returning_0_5,
            metric_returning_0_2,
            metric_returning_0_1,
        ]
        weights = [0.5, 0.3, 0.2]
        multi_metric = MultiMetricObjective(metrics=metrics, weights=weights)

        # Act
        result = multi_metric(dataset_item={}, llm_output="test output")

        # Assert
        # Expected: (0.5 * 0.5) + (0.2 * 0.3) + (0.1 * 0.2) = 0.25 + 0.06 + 0.02 = 0.33
        expected_value = 0.33
        assert result.value == pytest.approx(expected_value), (
            f"Expected weighted score {expected_value}, got {result.value}"
        )

    def test_metadata_contains_raw_score_results(self) -> None:
        """Test that metadata contains all raw score results from subscores"""
        # Arrange
        metrics: list[Callable[[dict[str, Any], str], ScoreResult]] = [
            metric_returning_0_5,
            metric_returning_0_2,
            metric_returning_0_1,
        ]
        weights = [0.4, 0.4, 0.2]
        multi_metric = MultiMetricObjective(metrics=metrics, weights=weights)

        # Act
        result = multi_metric(dataset_item={}, llm_output="test output")

        # Assert
        assert "raw_score_results" in result.metadata, (
            "Metadata should contain 'raw_score_results' key"
        )

        raw_scores = result.metadata["raw_score_results"]
        assert len(raw_scores) == 3, (
            f"Expected 3 raw score results, got {len(raw_scores)}"
        )

        # Verify each score result
        assert raw_scores[0].name == "metric_returning_0_5"
        assert raw_scores[0].value == 0.5

        assert raw_scores[1].name == "metric_returning_0_2"
        assert raw_scores[1].value == 0.2

        assert raw_scores[2].name == "metric_returning_0_1"
        assert raw_scores[2].value == 0.1

    def test_equal_weights_when_no_weights_provided__one_metric(self) -> None:
        """Test that single metric works correctly (edge case)"""
        # Arrange
        metrics: list[Callable[[dict[str, Any], str], ScoreResult]] = [
            metric_returning_0_5
        ]
        multi_metric = MultiMetricObjective(metrics=metrics)

        # Act
        result = multi_metric(dataset_item={}, llm_output="test output")

        # Assert
        assert multi_metric.weights == [1.0], (
            f"Expected weight [1.0], got {multi_metric.weights}"
        )

        # Expected: 0.5 * 1.0 = 0.5 (same as the single metric value)
        expected_value = 0.5
        assert result.value == pytest.approx(expected_value), (
            f"Expected weighted score {expected_value}, got {result.value}"
        )

        # Verify metadata contains the single metric
        raw_scores = result.metadata["raw_score_results"]
        assert len(raw_scores) == 1, "Should have exactly one raw score"
        assert raw_scores[0].name == "metric_returning_0_5"
        assert raw_scores[0].value == 0.5

    def test_equal_weights_when_no_weights_provided__two_metrics(self) -> None:
        """Test that weights are equal when not provided (two metrics)"""
        # Arrange
        metrics: list[Callable[[dict[str, Any], str], ScoreResult]] = [
            metric_returning_0_5,
            metric_returning_0_2,
        ]
        multi_metric = MultiMetricObjective(metrics=metrics)

        # Act
        result = multi_metric(dataset_item={}, llm_output="test output")

        # Assert
        assert multi_metric.weights == [0.5, 0.5], (
            f"Expected equal weights [0.5, 0.5], got {multi_metric.weights}"
        )

        # Expected: (0.5 + 0.2) / 2 = 0.7 / 2 = 0.35
        expected_value = 0.35
        assert result.value == pytest.approx(expected_value), (
            f"Expected weighted score {expected_value}, got {result.value}"
        )

    def test_equal_weights_when_no_weights_provided__three_metrics(self) -> None:
        """Test that weights are equal when not provided (three metrics)"""
        # Arrange
        metrics: list[Callable[[dict[str, Any], str], ScoreResult]] = [
            metric_returning_0_5,
            metric_returning_0_2,
            metric_returning_0_1,
        ]
        multi_metric = MultiMetricObjective(metrics=metrics)

        # Act
        result = multi_metric(dataset_item={}, llm_output="test output")

        # Assert
        expected_weight = 1 / 3
        assert all(w == pytest.approx(expected_weight) for w in multi_metric.weights), (
            f"Expected equal weights [1/3, 1/3, 1/3], got {multi_metric.weights}"
        )

        # Expected: (0.5 + 0.2 + 0.1) / 3 = 0.8 / 3 ≈ 0.2667
        expected_value = 0.8 / 3
        assert result.value == pytest.approx(expected_value), (
            f"Expected weighted score {expected_value}, got {result.value}"
        )

    def test_custom_name_is_set(self) -> None:
        """Test that custom name is properly set"""
        # Arrange
        metrics: list[Callable[[dict[str, Any], str], ScoreResult]] = [
            metric_returning_0_5,
            metric_returning_0_2,
        ]
        custom_name = "my_custom_objective"
        multi_metric = MultiMetricObjective(metrics=metrics, name=custom_name)

        # Act
        result = multi_metric(dataset_item={}, llm_output="test output")

        # Assert
        assert multi_metric.__name__ == custom_name, (
            f"Expected name '{custom_name}', got '{multi_metric.__name__}'"
        )
        assert result.name == custom_name, (
            f"Expected result name '{custom_name}', got '{result.name}'"
        )

    def test_default_name_is_set(self) -> None:
        """Test that default name is set when not provided"""
        # Arrange
        metrics: list[Callable[[dict[str, Any], str], ScoreResult]] = [
            metric_returning_0_5,
            metric_returning_0_2,
        ]
        multi_metric = MultiMetricObjective(metrics=metrics)

        # Act
        result = multi_metric(dataset_item={}, llm_output="test output")

        # Assert
        expected_name = "multi_metric_objective"
        assert multi_metric.__name__ == expected_name, (
            f"Expected default name '{expected_name}', got '{multi_metric.__name__}'"
        )
        assert result.name == expected_name, (
            f"Expected result name '{expected_name}', got '{result.name}'"
        )

    def test_dataset_item_and_llm_output_are_passed_to_metrics(self) -> None:
        """Test that dataset_item and llm_output are correctly passed to metric functions"""
        # Arrange
        received_items_1 = []
        received_items_2 = []
        received_outputs_1 = []
        received_outputs_2 = []

        def tracking_metric_1(
            dataset_item: dict[str, Any], llm_output: str
        ) -> ScoreResult:
            received_items_1.append(dataset_item)
            received_outputs_1.append(llm_output)
            return ScoreResult(name="tracking_metric", value=0.5)

        def tracking_metric_2(
            dataset_item: dict[str, Any], llm_output: str
        ) -> ScoreResult:
            received_items_2.append(dataset_item)
            received_outputs_2.append(llm_output)
            return ScoreResult(name="tracking_metric_returning_0_2", value=0.2)

        metrics: list[Callable[[dict[str, Any], str], ScoreResult]] = [
            tracking_metric_1,
            tracking_metric_2,
        ]
        multi_metric = MultiMetricObjective(metrics=metrics)
        test_item = {"input": "test input", "expected_output": "test output"}
        test_output = "generated response"

        multi_metric(dataset_item=test_item, llm_output=test_output)

        # Assert
        assert len(received_items_1) == 1, "Metric should have been called once"
        assert received_items_1[0] == test_item, "Dataset item should match"
        assert len(received_outputs_1) == 1, "Metric should have been called once"
        assert received_outputs_1[0] == test_output, "LLM output should match"
        assert len(received_items_2) == 1, "Metric should have been called once"
        assert received_items_2[0] == test_item, "Dataset item should match"
        assert len(received_outputs_2) == 1, "Metric should have been called once"
        assert received_outputs_2[0] == test_output, "LLM output should match"

    def test_weights_sum_not_required_to_be_one(self) -> None:
        """Test that weights don't need to sum to 1.0"""
        # Arrange
        metrics: list[Callable[[dict[str, Any], str], ScoreResult]] = [
            metric_returning_0_5,
            metric_returning_0_2,
        ]
        weights = [2.0, 3.0]  # Sum is 5.0, not 1.0
        multi_metric = MultiMetricObjective(metrics=metrics, weights=weights)

        # Act
        result = multi_metric(dataset_item={}, llm_output="test output")

        # Assert
        # Expected: (0.5 * 2.0) + (0.2 * 3.0) = 1.0 + 0.6 = 1.6
        expected_value = 1.6
        assert result.value == pytest.approx(expected_value), (
            f"Expected weighted score {expected_value}, got {result.value}"
        )

    def test_zero_weight_excludes_metric_from_result(self) -> None:
        """Test that a metric with zero weight doesn't contribute to final score"""
        # Arrange
        metrics: list[Callable[[dict[str, Any], str], ScoreResult]] = [
            metric_returning_0_5,
            metric_returning_0_2,
            metric_returning_0_1,
        ]
        weights = [0.5, 0.0, 0.5]  # metric_returning_0_2 has zero weight
        multi_metric = MultiMetricObjective(metrics=metrics, weights=weights)

        # Act
        result = multi_metric(dataset_item={}, llm_output="test output")

        # Assert
        # Expected: (0.5 * 0.5) + (0.2 * 0.0) + (0.1 * 0.5) = 0.25 + 0.0 + 0.05 = 0.3
        expected_value = 0.3
        assert result.value == pytest.approx(expected_value), (
            f"Expected weighted score {expected_value}, got {result.value}"
        )

        # Verify metric_returning_0_2 is still in metadata even though it has zero weight
        raw_scores = result.metadata["raw_score_results"]
        assert len(raw_scores) == 3, "All metrics should be in metadata"
        assert raw_scores[1].name == "metric_returning_0_2"

    def test_default_reason_is_generated(self) -> None:
        """Test that a default reason is generated when none is provided"""
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
        """Test that reason overrides are respected"""
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
        """Test that reason_builder can customize the reason"""
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

    def test_float_metric_is_wrapped_in_score_result(self) -> None:
        """Test that float metric results are normalized to ScoreResult"""

        def metric_returning_float(
            dataset_item: dict[str, Any], llm_output: str
        ) -> float:
            return 0.7

        multi_metric = MultiMetricObjective(metrics=[metric_returning_float])

        result = multi_metric(dataset_item={}, llm_output="test output")

        raw_scores = result.metadata["raw_score_results"]
        assert len(raw_scores) == 1
        assert raw_scores[0].name == "metric_returning_float"
        assert raw_scores[0].value == pytest.approx(0.7)

    def test_base_metric_is_supported(self) -> None:
        """Test that BaseMetric instances can be used directly"""

        class DummyMetric(base_metric.BaseMetric):
            def __init__(self) -> None:
                super().__init__(name="dummy_metric", track=False)

            def score(self, output: str, expected: str, **kwargs: Any) -> ScoreResult:
                value = 1.0 if output == expected else 0.0
                return ScoreResult(name=self.name, value=value)

        multi_metric = MultiMetricObjective(metrics=[DummyMetric()])
        result = multi_metric(dataset_item={"expected": "ok"}, llm_output="ok")

        assert result.value == pytest.approx(1.0)

    def test_list_returning_metric_raises(self) -> None:
        """Test that list-returning metrics raise when multiple results are returned"""

        def metric_returning_list(
            dataset_item: dict[str, Any], llm_output: str
        ) -> list[ScoreResult]:
            return [
                ScoreResult(name="first", value=0.1),
                ScoreResult(name="second", value=0.2),
            ]

        multi_metric = MultiMetricObjective(metrics=[metric_returning_list])

        with pytest.raises(ValueError, match="single ScoreResult"):
            multi_metric(dataset_item={}, llm_output="test output")

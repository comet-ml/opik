import pytest
from typing import Any
from collections.abc import Callable
from unittest.mock import Mock

from opik.evaluation.metrics.score_result import ScoreResult
from opik.message_processing.emulation.models import SpanModel
from opik_optimizer.metrics.multi_metric_objective import MultiMetricObjective
from opik_optimizer.metrics import helpers


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

    def test_metric_with_task_span_signature__call_includes_task_span(self) -> None:
        """Test that __call__ includes task_span parameter when at least one metric needs it"""

        # Arrange
        def metric_with_task_span(
            dataset_item: dict[str, Any], llm_output: str, task_span: SpanModel | None
        ) -> ScoreResult:
            return ScoreResult(name="metric_with_task_span", value=0.5)

        metrics: list[Callable] = [metric_with_task_span]
        multi_metric = MultiMetricObjective(metrics=metrics)

        # Act & Assert
        # The __call__ method should accept task_span parameter
        assert helpers.has_task_span_parameter(multi_metric.__call__), (
            "MultiMetricObjective with task_span metrics should have task_span in __call__ signature"
        )

        # Should work with task_span provided
        mock_span = Mock(spec=SpanModel)
        result = multi_metric(dataset_item={}, llm_output="test", task_span=mock_span)
        assert result.value == 0.5

    def test_metric_without_task_span_signature__call_excludes_task_span(self) -> None:
        """Test that __call__ excludes task_span parameter when no metric needs it"""
        # Arrange
        metrics: list[Callable[[dict[str, Any], str], ScoreResult]] = [
            metric_returning_0_5,
            metric_returning_0_2,
        ]
        multi_metric = MultiMetricObjective(metrics=metrics)

        # Act & Assert
        # The __call__ method should NOT have task_span parameter
        assert not helpers.has_task_span_parameter(multi_metric.__call__), (
            "MultiMetricObjective without task_span metrics should not have task_span in __call__ signature"
        )

        # Should work without task_span
        result = multi_metric(dataset_item={}, llm_output="test")
        assert result.value == pytest.approx(0.35)

    def test_mixed_metrics_with_and_without_task_span(self) -> None:
        """Test that MultiMetricObjective works with mix of metrics with/without task_span"""
        # Arrange
        received_spans = []

        def metric_with_task_span(
            dataset_item: dict[str, Any], llm_output: str, task_span: SpanModel | None
        ) -> ScoreResult:
            received_spans.append(task_span)
            return ScoreResult(name="metric_with_task_span", value=0.4)

        def metric_without_task_span(
            dataset_item: dict[str, Any], llm_output: str
        ) -> ScoreResult:
            return ScoreResult(name="metric_without_task_span", value=0.6)

        metrics: list[Callable] = [
            metric_with_task_span,
            metric_without_task_span,
        ]
        weights = [0.5, 0.5]
        multi_metric = MultiMetricObjective(metrics=metrics, weights=weights)

        # Act
        mock_span = Mock(spec=SpanModel)
        result = multi_metric(dataset_item={}, llm_output="test", task_span=mock_span)

        # Assert
        # Should have task_span in signature since at least one metric needs it
        assert helpers.has_task_span_parameter(multi_metric.__call__), (
            "MultiMetricObjective with mixed metrics should have task_span in __call__ signature"
        )

        # Expected: (0.4 * 0.5) + (0.6 * 0.5) = 0.2 + 0.3 = 0.5
        expected_value = 0.5
        assert result.value == pytest.approx(expected_value), (
            f"Expected weighted score {expected_value}, got {result.value}"
        )

        # Verify task_span was passed to metric that needs it
        assert len(received_spans) == 1, (
            "Metric with task_span should have been called once"
        )
        assert received_spans[0] is mock_span, "Task span should be passed through"

        # Verify both metrics are in metadata
        raw_scores = result.metadata["raw_score_results"]
        assert len(raw_scores) == 2, "Should have both metrics in metadata"
        assert raw_scores[0].name == "metric_with_task_span"
        assert raw_scores[0].value == 0.4
        assert raw_scores[1].name == "metric_without_task_span"
        assert raw_scores[1].value == 0.6

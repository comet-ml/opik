import pytest
from datetime import datetime
from unittest.mock import patch

import opik.exceptions
from opik.message_processing.emulation.models import SpanModel
from opik_optimizer.metrics import TotalSpanCost


class TestTotalSpanCost:
    def test_calculates_cost_from_usage__happyflow(self) -> None:
        """Test that cost is calculated correctly from token usage"""
        # Arrange
        metric = TotalSpanCost()
        span = SpanModel(
            id="span-test",
            type="llm",
            name="test_span",
            project_name="test-project",
            start_time=datetime.now(),
            end_time=datetime.now(),
            model="gpt-3.5-turbo",
            usage={"prompt_tokens": 100, "completion_tokens": 50},
            total_cost=None,
            spans=[],
        )

        # Mock LiteLLM cost calculation
        with patch("litellm.cost_per_token") as mock_cost:
            # litellm.cost_per_token returns (total_prompt_cost, total_completion_cost)
            mock_cost.return_value = (
                0.01,
                0.01,
            )  # Total costs for prompt and completion

            # Act
            result = metric.score(task_span=span)

            # Assert
            # Cost: 0.01 + 0.01 = 0.02
            expected_cost = 0.02
            assert result.value == pytest.approx(expected_cost)
            assert result.name == "total_span_cost"
            assert result.metadata["processed_span_count"] == 1
            assert result.metadata["total_prompt_tokens"] == 100
            assert result.metadata["total_completion_tokens"] == 50

    def test_uses_existing_total_cost_when_available(self) -> None:
        """Test that existing total_cost is used instead of calculating"""
        # Arrange
        metric = TotalSpanCost()
        span = SpanModel(
            id="span-test",
            type="llm",
            name="test_span",
            project_name="test-project",
            start_time=datetime.now(),
            end_time=datetime.now(),
            model="gpt-3.5-turbo",
            usage={"prompt_tokens": 100, "completion_tokens": 50},
            total_cost=0.05,  # Pre-calculated cost
            spans=[],
        )

        # Act
        result = metric.score(task_span=span)

        # Assert
        assert result.value == 0.05
        assert result.metadata["processed_span_count"] == 1

    def test_traverses_nested_spans__accumulates_costs(self) -> None:
        """Test that nested spans are traversed and costs are accumulated"""
        # Arrange
        metric = TotalSpanCost()

        root_span = SpanModel(
            id="span-root",
            type="llm",
            name="root",
            project_name="test-project",
            start_time=datetime.now(),
            end_time=datetime.now(),
            total_cost=0.03,
            spans=[
                SpanModel(
                    id="span-nested1",
                    type="llm",
                    name="nested1",
                    project_name="test-project",
                    start_time=datetime.now(),
                    end_time=datetime.now(),
                    total_cost=0.01,
                    spans=[],
                ),
                SpanModel(
                    id="span-nested2",
                    type="llm",
                    name="nested2",
                    project_name="test-project",
                    start_time=datetime.now(),
                    end_time=datetime.now(),
                    total_cost=0.02,
                    spans=[],
                ),
            ],
        )

        # Act
        result = metric.score(task_span=root_span)

        # Assert
        # Total: 0.03 + 0.01 + 0.02 = 0.06
        expected_cost = 0.06
        assert result.value == pytest.approx(expected_cost)
        assert result.metadata["processed_span_count"] == 3

    def test_skips_spans_without_usage_data(self) -> None:
        """Test that spans without usage data are skipped"""
        # Arrange
        metric = TotalSpanCost()

        root_span = SpanModel(
            id="span-root",
            type="llm",
            name="root",
            project_name="test-project",
            start_time=datetime.now(),
            end_time=datetime.now(),
            spans=[
                SpanModel(
                    id="span-with-cost",
                    type="llm",
                    name="with_cost",
                    project_name="test-project",
                    start_time=datetime.now(),
                    end_time=datetime.now(),
                    total_cost=0.05,
                    spans=[],
                ),
                SpanModel(
                    id="span-without-usage",
                    type="llm",
                    name="without_usage",
                    project_name="test-project",
                    start_time=datetime.now(),
                    end_time=datetime.now(),
                    model="gpt-3.5-turbo",
                    usage=None,
                    spans=[],
                ),
            ],
        )

        # Act
        result = metric.score(task_span=root_span)

        # Assert
        # Only the span with total_cost should be counted
        assert result.value == 0.05
        assert result.metadata["processed_span_count"] == 1

    def test_skips_spans_with_zero_tokens(self) -> None:
        """Test that spans with zero tokens are skipped"""
        # Arrange
        metric = TotalSpanCost()
        span = SpanModel(
            id="span-test",
            type="llm",
            name="test_span",
            project_name="test-project",
            start_time=datetime.now(),
            end_time=datetime.now(),
            model="gpt-3.5-turbo",
            usage={"prompt_tokens": 0, "completion_tokens": 0},
            spans=[],
        )

        # Act
        result = metric.score(task_span=span)

        # Assert
        assert result.value == 0.0
        assert result.metadata["processed_span_count"] == 0

    def test_raises_error_when_all_spans_fail_cost_calculation(self) -> None:
        """Test that MetricComputationError is raised when all spans fail cost calculation"""
        # Arrange
        metric = TotalSpanCost()
        span = SpanModel(
            id="span-test",
            type="llm",
            name="test_span",
            project_name="test-project",
            start_time=datetime.now(),
            end_time=datetime.now(),
            model="unknown-model",
            usage={"prompt_tokens": 100, "completion_tokens": 50},
            spans=[],
        )

        # Mock LiteLLM to fail cost calculation
        with patch("litellm.cost_per_token") as mock_cost:
            mock_cost.side_effect = Exception("Unknown model")

            # Act & Assert
            with pytest.raises(opik.exceptions.MetricComputationError) as exc_info:
                metric.score(task_span=span)

            assert "Failed to calculate cost for all 1 span(s)" in str(exc_info.value)

    def test_handles_mixed_success_and_failure__returns_partial_results(self) -> None:
        """Test that partial results are returned when some spans fail cost calculation"""
        # Arrange
        metric = TotalSpanCost()

        root_span = SpanModel(
            id="span-root",
            type="llm",
            name="root",
            project_name="test-project",
            start_time=datetime.now(),
            end_time=datetime.now(),
            spans=[
                SpanModel(
                    id="span-success",
                    type="llm",
                    name="success",
                    project_name="test-project",
                    start_time=datetime.now(),
                    end_time=datetime.now(),
                    total_cost=0.05,
                    spans=[],
                ),
                SpanModel(
                    id="span-failing",
                    type="llm",
                    name="failing",
                    project_name="test-project",
                    start_time=datetime.now(),
                    end_time=datetime.now(),
                    model="unknown-model",
                    usage={"prompt_tokens": 100, "completion_tokens": 50},
                    spans=[],
                ),
            ],
        )

        # Mock LiteLLM to fail for unknown model
        with patch("litellm.cost_per_token") as mock_cost:
            mock_cost.side_effect = Exception("Unknown model")

            # Act
            result = metric.score(task_span=root_span)

            # Assert
            # Should return cost from successful span only
            assert result.value == 0.05
            assert result.metadata["processed_span_count"] == 1
            assert result.metadata["failed_span_count"] == 1

    def test_custom_metric_name(self) -> None:
        """Test that custom metric name is used"""
        # Arrange
        custom_name = "my_custom_cost_metric"
        metric = TotalSpanCost(name=custom_name)
        span = SpanModel(
            id="span-test",
            type="llm",
            name="test_span",
            project_name="test-project",
            start_time=datetime.now(),
            end_time=datetime.now(),
            total_cost=0.05,
            spans=[],
        )

        # Act
        result = metric.score(task_span=span)

        # Assert
        assert result.name == custom_name

    def test_handles_deeply_nested_spans(self) -> None:
        """Test that deeply nested span trees are handled correctly"""
        # Arrange
        metric = TotalSpanCost()

        # Create a deep hierarchy: root -> child -> grandchild
        root = SpanModel(
            id="span-root",
            type="llm",
            name="root",
            project_name="test-project",
            start_time=datetime.now(),
            end_time=datetime.now(),
            total_cost=0.03,
            spans=[
                SpanModel(
                    id="span-child",
                    type="llm",
                    name="child",
                    project_name="test-project",
                    start_time=datetime.now(),
                    end_time=datetime.now(),
                    total_cost=0.02,
                    spans=[
                        SpanModel(
                            id="span-grandchild",
                            type="llm",
                            name="grandchild",
                            project_name="test-project",
                            start_time=datetime.now(),
                            end_time=datetime.now(),
                            total_cost=0.01,
                            spans=[],
                        ),
                    ],
                ),
            ],
        )

        # Act
        result = metric.score(task_span=root)

        # Assert
        # Total: 0.03 + 0.02 + 0.01 = 0.06
        assert result.value == pytest.approx(0.06)
        assert result.metadata["processed_span_count"] == 3

    def test_metadata_contains_all_required_fields(self) -> None:
        """Test that result metadata contains all required fields"""
        # Arrange
        metric = TotalSpanCost()
        span = SpanModel(
            id="span-test",
            type="llm",
            name="test_span",
            project_name="test-project",
            start_time=datetime.now(),
            end_time=datetime.now(),
            total_cost=0.05,
            spans=[],
        )

        # Act
        result = metric.score(task_span=span)

        # Assert
        assert "total_cost" in result.metadata
        assert "total_prompt_tokens" in result.metadata
        assert "total_completion_tokens" in result.metadata
        assert "processed_span_count" in result.metadata
        assert "failed_span_count" in result.metadata

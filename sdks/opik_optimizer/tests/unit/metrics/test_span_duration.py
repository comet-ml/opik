import pytest
from datetime import datetime

import opik.exceptions
from opik.message_processing.emulation.models import SpanModel
from opik_optimizer.metrics import SpanDuration


class TestSpanDuration:
    def test_calculates_duration__happyflow(self) -> None:
        """Test that duration is calculated correctly from start and end times"""
        # Arrange
        metric = SpanDuration()
        start_time = datetime(2024, 1, 1, 12, 0, 0)
        end_time = datetime(2024, 1, 1, 12, 0, 2, 500000)  # 2.5 seconds later
        span = SpanModel(
            id="span-test",
            type="llm",
            name="test_span",
            project_name="test-project",
            start_time=start_time,
            end_time=end_time,
            spans=[],
        )

        # Act
        result = metric.score(task_span=span)

        # Assert
        assert result.value == pytest.approx(2.5, abs=0.001)
        assert result.name == "total_span_duration"

    def test_raises_error_when_end_time_is_none(self) -> None:
        """Test that MetricComputationError is raised when end_time is None"""
        # Arrange
        metric = SpanDuration()
        span = SpanModel(
            id="span-test",
            type="llm",
            name="test_span",
            project_name="test-project",
            start_time=datetime.now(),
            end_time=None,
            spans=[],
        )

        # Act & Assert
        with pytest.raises(opik.exceptions.MetricComputationError) as exc_info:
            metric.score(task_span=span)

        assert "end time or start time is not set" in str(exc_info.value)

    def test_raises_error_when_start_time_is_none(self) -> None:
        """Test that MetricComputationError is raised when start_time is None"""
        # Arrange
        metric = SpanDuration()
        span = SpanModel(
            id="span-test",
            type="llm",
            name="test_span",
            project_name="test-project",
            start_time=None,
            end_time=datetime.now(),
            spans=[],
        )

        # Act & Assert
        with pytest.raises(opik.exceptions.MetricComputationError) as exc_info:
            metric.score(task_span=span)

        assert "end time or start time is not set" in str(exc_info.value)

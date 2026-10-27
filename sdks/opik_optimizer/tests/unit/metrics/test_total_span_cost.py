from datetime import datetime
from unittest.mock import patch
from typing import Any

import pytest
import opik.exceptions
from opik.message_processing.emulation.models import SpanModel
from opik_optimizer.metrics import SpanCost


NOW = datetime(2020, 1, 1)


def make_span(
    *,
    span_id: str = "span-test",
    name: str = "test_span",
    model: str | None = None,
    usage: dict[str, int] | None = None,
    total_cost: float | None = None,
    spans: list[SpanModel] | None = None,
) -> SpanModel:
    """Create a SpanModel with consistent defaults for tests."""
    return SpanModel(
        id=span_id,
        type="llm",
        name=name,
        project_name="test-project",
        start_time=NOW,
        end_time=NOW,
        model=model,
        usage=usage,
        total_cost=total_cost,
        spans=spans or [],
        source="optimization",
    )


def assert_cost_result(
    *,
    result: Any,
    expected_cost: float,
    processed_span_count: int,
    total_prompt_tokens: int = 0,
    total_completion_tokens: int = 0,
    failed_span_count: int = 0,
    metric_name: str = "span_cost",
) -> None:
    assert result.name == metric_name
    assert result.value == pytest.approx(expected_cost)
    assert result.metadata["processed_span_count"] == processed_span_count
    assert result.metadata["failed_span_count"] == failed_span_count
    assert result.metadata["total_prompt_tokens"] == total_prompt_tokens
    assert result.metadata["total_completion_tokens"] == total_completion_tokens


class TestSpanCost:
    def test_calculates_cost_from_usage__happyflow(self) -> None:
        """Test that cost is calculated correctly from token usage"""
        metric = SpanCost()
        span = make_span(
            model="gpt-3.5-turbo",
            usage={"prompt_tokens": 100, "completion_tokens": 50},
            total_cost=None,
        )

        # Mock LiteLLM cost calculation
        with patch("litellm.cost_per_token") as mock_cost:
            # litellm.cost_per_token returns (total_prompt_cost, total_completion_cost)
            mock_cost.return_value = (0.01, 0.01)

            result = metric.score(task_span=span)

        assert_cost_result(
            result=result,
            expected_cost=0.02,
            processed_span_count=1,
            total_prompt_tokens=100,
            total_completion_tokens=50,
        )

    def test_traverses_nested_spans__accumulates_costs(self) -> None:
        """Test that nested spans are traversed and costs are accumulated"""
        metric = SpanCost()

        root_span = make_span(
            span_id="span-root",
            name="root",
            total_cost=0.03,
            spans=[
                make_span(span_id="span-nested1", name="nested1", total_cost=0.01),
                make_span(span_id="span-nested2", name="nested2", total_cost=0.02),
            ],
        )

        result = metric.score(task_span=root_span)

        assert_cost_result(result=result, expected_cost=0.06, processed_span_count=3)

    def test_raises_error_when_all_spans_fail_cost_calculation(self) -> None:
        """Test that MetricComputationError is raised when all spans fail cost calculation"""
        metric = SpanCost()
        span = make_span(
            model="unknown-model",
            usage={"prompt_tokens": 100, "completion_tokens": 50},
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
        metric = SpanCost()

        root_span = make_span(
            span_id="span-root",
            name="root",
            spans=[
                make_span(span_id="span-success", name="success", total_cost=0.05),
                make_span(
                    span_id="span-failing",
                    name="failing",
                    model="unknown-model",
                    usage={"prompt_tokens": 100, "completion_tokens": 50},
                ),
            ],
        )

        # Mock LiteLLM to fail for unknown model
        with patch("litellm.cost_per_token") as mock_cost:
            mock_cost.side_effect = Exception("Unknown model")

            result = metric.score(task_span=root_span)

        assert_cost_result(
            result=result,
            expected_cost=0.05,
            processed_span_count=1,
            failed_span_count=1,
        )

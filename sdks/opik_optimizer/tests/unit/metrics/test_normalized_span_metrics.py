from datetime import datetime, timedelta

import pytest
from opik.message_processing.emulation.models import SpanModel
from opik_optimizer.metrics import TotalSpanCost, SpanDuration


def _make_span(
    *,
    total_cost: float | None = None,
    start_time: datetime | None = None,
    end_time: datetime | None = None,
) -> SpanModel:
    now = datetime(2024, 1, 1, 12, 0, 0)
    return SpanModel(
        id="span-test",
        type="llm",
        name="test_span",
        project_name="test-project",
        start_time=start_time or now,
        end_time=end_time or now,
        total_cost=total_cost,
        spans=[],
    )


def test_normalized_span_cost_returns_1_for_zero_cost() -> None:
    metric = TotalSpanCost(target=0.01, name="cost")
    result = metric.score(task_span=_make_span(total_cost=0.0))

    assert result.name == "cost"
    assert result.value == pytest.approx(1.0)
    assert result.metadata["raw_total_span_cost_usd"] == pytest.approx(0.0)


def test_normalized_span_cost_scales_against_target() -> None:
    metric = TotalSpanCost(target=0.01, name="cost")
    result = metric.score(task_span=_make_span(total_cost=0.01))

    # 1 / (1 + 1.0) = 0.5
    assert result.value == pytest.approx(0.5)
    assert result.metadata["target_cost_usd"] == pytest.approx(0.01)


def test_normalized_span_duration_returns_1_for_zero_duration() -> None:
    now = datetime(2024, 1, 1, 12, 0, 0)
    metric = SpanDuration(target=6.0, name="duration")
    result = metric.score(task_span=_make_span(start_time=now, end_time=now))

    assert result.name == "duration"
    assert result.value == pytest.approx(1.0)
    assert result.metadata["raw_total_span_duration_seconds"] == pytest.approx(0.0)


def test_normalized_span_duration_scales_against_target() -> None:
    now = datetime(2024, 1, 1, 12, 0, 0)
    metric = SpanDuration(target=6.0, name="duration")
    result = metric.score(
        task_span=_make_span(start_time=now, end_time=now + timedelta(seconds=6))
    )

    # 1 / (1 + 1.0) = 0.5
    assert result.value == pytest.approx(0.5)
    assert result.metadata["target_duration_seconds"] == pytest.approx(6.0)


def test_total_span_cost_rejects_non_positive_target() -> None:
    with pytest.raises(ValueError, match="target"):
        TotalSpanCost(target=0)


def test_span_duration_rejects_non_positive_target() -> None:
    with pytest.raises(ValueError, match="target"):
        SpanDuration(target=-1.0)


def test_total_span_cost_rejects_ambiguous_target_inputs() -> None:
    with pytest.raises(ValueError, match="both `target` and `target_cost_usd`"):
        TotalSpanCost(target=0.01, target_cost_usd=0.01)


def test_span_duration_rejects_ambiguous_target_inputs() -> None:
    with pytest.raises(
        ValueError, match="both `target` and `target_duration_seconds`"
    ):
        SpanDuration(target=6.0, target_duration_seconds=6.0)


def test_normalized_span_cost_missing_task_span_returns_neutral_failed() -> None:
    metric = TotalSpanCost(target=0.01, name="cost")
    result = metric.score(task_span=None)

    assert result.value == pytest.approx(0.0)
    assert result.scoring_failed is True
    assert "task_span" in (result.reason or "")


def test_normalized_span_duration_missing_task_span_returns_neutral_failed() -> None:
    metric = SpanDuration(target=6.0, name="duration")
    result = metric.score(task_span=None)

    assert result.value == pytest.approx(0.0)
    assert result.scoring_failed is True
    assert "task_span" in (result.reason or "")

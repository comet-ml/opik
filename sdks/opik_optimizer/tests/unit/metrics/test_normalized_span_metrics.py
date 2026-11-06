from datetime import datetime, timedelta

import pytest
from opik.message_processing.emulation.models import SpanModel
from opik_optimizer.metrics import SpanCost, SpanDuration


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
        source="optimization",
    )


def test_normalized_span_cost_scales_against_target() -> None:
    metric = SpanCost(target=0.01, name="cost")
    result = metric.score(task_span=_make_span(total_cost=0.01))

    # 1 / (1 + 1.0) = 0.5
    assert result.value == pytest.approx(0.5)
    assert result.metadata["_target_cost_usd"] == pytest.approx(0.01)


def test_normalized_span_duration_scales_against_target() -> None:
    now = datetime(2024, 1, 1, 12, 0, 0)
    metric = SpanDuration(target=6.0, name="duration")
    result = metric.score(
        task_span=_make_span(start_time=now, end_time=now + timedelta(seconds=6))
    )

    # 1 / (1 + 1.0) = 0.5
    assert result.value == pytest.approx(0.5)
    assert result.metadata["_target_duration_seconds"] == pytest.approx(6.0)


def test_span_cost_rejects_non_positive_target() -> None:
    with pytest.raises(ValueError, match="target"):
        SpanCost(target=0)


def test_normalized_span_cost_missing_task_span_returns_neutral_failed() -> None:
    metric = SpanCost(target=0.01, name="cost")
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

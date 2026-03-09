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
    )


def test_normalized_span_cost_returns_1_for_zero_cost() -> None:
    metric = SpanCost(target=0.01, name="cost")
    result = metric.score(task_span=_make_span(total_cost=0.0))

    assert result.name == "cost"
    assert result.value == pytest.approx(1.0)
    assert result.metadata["_raw_span_cost_usd"] == pytest.approx(0.0)


def test_normalized_span_cost_scales_against_target() -> None:
    metric = SpanCost(target=0.01, name="cost")
    result = metric.score(task_span=_make_span(total_cost=0.01))

    # 1 / (1 + 1.0) = 0.5
    assert result.value == pytest.approx(0.5)
    assert result.metadata["_target_cost_usd"] == pytest.approx(0.01)


def test_normalized_span_cost_respects_invert_false() -> None:
    metric = SpanCost(target=0.01, invert=False, name="cost")
    result = metric.score(task_span=_make_span(total_cost=0.01))

    # normalized=1.0 -> 1 / (1 + 1.0) = 0.5 for both directions at midpoint
    assert result.value == pytest.approx(0.5)
    assert result.metadata["_invert"] is False


def test_normalized_span_cost_directionality_changes_with_invert() -> None:
    low_cost_span = _make_span(total_cost=0.002)
    high_cost_span = _make_span(total_cost=0.02)

    invert_metric = SpanCost(target=0.01, invert=True, name="cost")
    non_invert_metric = SpanCost(target=0.01, invert=False, name="cost")

    invert_low = invert_metric.score(task_span=low_cost_span).value
    invert_high = invert_metric.score(task_span=high_cost_span).value
    non_invert_low = non_invert_metric.score(task_span=low_cost_span).value
    non_invert_high = non_invert_metric.score(task_span=high_cost_span).value

    assert invert_low > invert_high
    assert non_invert_low < non_invert_high


def test_normalized_span_duration_returns_1_for_zero_duration() -> None:
    now = datetime(2024, 1, 1, 12, 0, 0)
    metric = SpanDuration(target=6.0, name="duration")
    result = metric.score(task_span=_make_span(start_time=now, end_time=now))

    assert result.name == "duration"
    assert result.value == pytest.approx(1.0)
    assert result.metadata["_raw_span_duration_seconds"] == pytest.approx(0.0)


def test_normalized_span_duration_scales_against_target() -> None:
    now = datetime(2024, 1, 1, 12, 0, 0)
    metric = SpanDuration(target=6.0, name="duration")
    result = metric.score(
        task_span=_make_span(start_time=now, end_time=now + timedelta(seconds=6))
    )

    # 1 / (1 + 1.0) = 0.5
    assert result.value == pytest.approx(0.5)
    assert result.metadata["_target_duration_seconds"] == pytest.approx(6.0)


def test_normalized_span_duration_respects_invert_false() -> None:
    now = datetime(2024, 1, 1, 12, 0, 0)
    metric = SpanDuration(target=6.0, invert=False, name="duration")
    result = metric.score(
        task_span=_make_span(start_time=now, end_time=now + timedelta(seconds=6))
    )

    assert result.value == pytest.approx(0.5)
    assert result.metadata["_invert"] is False


def test_normalized_span_duration_directionality_changes_with_invert() -> None:
    now = datetime(2024, 1, 1, 12, 0, 0)
    short_span = _make_span(start_time=now, end_time=now + timedelta(seconds=2))
    long_span = _make_span(start_time=now, end_time=now + timedelta(seconds=20))

    invert_metric = SpanDuration(target=6.0, invert=True, name="duration")
    non_invert_metric = SpanDuration(target=6.0, invert=False, name="duration")

    invert_short = invert_metric.score(task_span=short_span).value
    invert_long = invert_metric.score(task_span=long_span).value
    non_invert_short = non_invert_metric.score(task_span=short_span).value
    non_invert_long = non_invert_metric.score(task_span=long_span).value

    assert invert_short > invert_long
    assert non_invert_short < non_invert_long


def test_span_cost_rejects_non_positive_target() -> None:
    with pytest.raises(ValueError, match="target"):
        SpanCost(target=0)


def test_span_duration_rejects_non_positive_target() -> None:
    with pytest.raises(ValueError, match="target"):
        SpanDuration(target=-1.0)


def test_span_cost_rejects_ambiguous_target_inputs() -> None:
    with pytest.raises(ValueError, match="both `target` and `target_cost_usd`"):
        SpanCost(target=0.01, target_cost_usd=0.01)


def test_span_duration_preserves_legacy_positional_track_parameter() -> None:
    metric = SpanDuration("duration", False)
    assert metric.track is False


def test_span_cost_preserves_legacy_positional_track_parameter() -> None:
    metric = SpanCost("cost", False)
    assert metric.track is False


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

"""
Shared assertion helpers for optimizer tests.

Keep these helpers small and focused: they should reduce repetition without
hiding important intent in tests.
"""

from __future__ import annotations

from typing import Any, Protocol

import pytest


class _OptimizerLike(Protocol):
    def optimize_prompt(self, *args: Any, **kwargs: Any) -> Any: ...


def assert_baseline_early_stop(
    result: Any,
    *,
    perfect_score: float = 0.95,
    trials_completed: int | None = None,
    history_len: int | None = None,
) -> None:
    """
    Assert the standard BaseOptimizer early-stop contract when baseline >= perfect_score.
    """
    assert result.details["stopped_early"] is True
    assert result.details["stop_reason"] == "baseline_score_met_threshold"
    assert result.details["perfect_score"] == perfect_score
    assert result.initial_score == result.score
    if trials_completed is not None:
        assert result.details["trials_completed"] == trials_completed
    if history_len is not None:
        assert len(result.history) == history_len


def assert_invalid_prompt_raises(
    optimizer: _OptimizerLike,
    *,
    dataset: Any,
    metric: Any,
    max_trials: int = 1,
) -> None:
    """
    Assert a standard error for invalid prompt input across optimizers.
    """
    with pytest.raises((ValueError, TypeError)):
        optimizer.optimize_prompt(
            prompt="invalid string",  # type: ignore[arg-type]
            dataset=dataset,
            metric=metric,
            max_trials=max_trials,
        )


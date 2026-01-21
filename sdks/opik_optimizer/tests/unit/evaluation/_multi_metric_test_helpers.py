"""Shared helpers for MultiMetricObjective unit tests."""

from __future__ import annotations

from typing import Any

from opik.evaluation.metrics.score_result import ScoreResult


def make_constant_metric(name: str, value: float) -> Any:
    def metric(_dataset_item: dict[str, Any], _llm_output: str) -> ScoreResult:
        return ScoreResult(name=name, value=value)

    metric.__name__ = name
    return metric

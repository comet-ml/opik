from __future__ import annotations

from typing import Any
from collections.abc import Callable

import pytest

from opik.evaluation.metrics.score_result import ScoreResult
from opik_optimizer.metrics.multi_metric_objective import MultiMetricObjective
from tests.unit.evaluation._multi_metric_test_helpers import make_constant_metric


metric_returning_0_5 = make_constant_metric("metric_returning_0_5", 0.5)
metric_returning_0_2 = make_constant_metric("metric_returning_0_2", 0.2)
metric_returning_0_1 = make_constant_metric("metric_returning_0_1", 0.1)


class TestMultiMetricObjectiveDefaults:
    def test_equal_weights_when_no_weights_provided__two_metrics(self) -> None:
        metrics: list[Callable[[dict[str, Any], str], ScoreResult]] = [
            metric_returning_0_5,
            metric_returning_0_2,
        ]
        multi_metric = MultiMetricObjective(metrics=metrics)

        result = multi_metric(dataset_item={}, llm_output="test output")

        assert multi_metric.weights == [0.5, 0.5], (
            f"Expected equal weights [0.5, 0.5], got {multi_metric.weights}"
        )

        expected_value = 0.35
        assert result.value == pytest.approx(expected_value), (
            f"Expected weighted score {expected_value}, got {result.value}"
        )

from __future__ import annotations

from typing import Any
from collections.abc import Callable

import pytest

from opik.evaluation.metrics import base_metric
from opik.evaluation.metrics.score_result import ScoreResult
from opik_optimizer.metrics.multi_metric_objective import MultiMetricObjective


class TestMultiMetricObjectiveInputs:
    def test_dataset_item_and_llm_output_are_passed_to_metrics(self) -> None:
        received_items_1: list[dict[str, Any]] = []
        received_items_2: list[dict[str, Any]] = []
        received_outputs_1: list[str] = []
        received_outputs_2: list[str] = []

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

        assert received_items_1 == [test_item]
        assert received_outputs_1 == [test_output]
        assert received_items_2 == [test_item]
        assert received_outputs_2 == [test_output]


class TestMultiMetricObjectiveNormalization:
    def test_float_metric_is_wrapped_in_score_result(self) -> None:
        def metric_returning_float(
            dataset_item: dict[str, Any], llm_output: str
        ) -> float:
            _ = dataset_item, llm_output
            return 0.7

        multi_metric = MultiMetricObjective(metrics=[metric_returning_float])

        result = multi_metric(dataset_item={}, llm_output="test output")

        raw_scores = result.metadata["raw_score_results"]
        assert len(raw_scores) == 1
        assert raw_scores[0].name == "metric_returning_float"
        assert raw_scores[0].value == pytest.approx(0.7)

    def test_base_metric_is_supported(self) -> None:
        class DummyMetric(base_metric.BaseMetric):
            def __init__(self) -> None:
                super().__init__(name="dummy_metric", track=False)

            def score(self, output: str, expected: str, **kwargs: Any) -> ScoreResult:
                value = 1.0 if output == expected else 0.0
                return ScoreResult(name=self.name, value=value)

        multi_metric = MultiMetricObjective(metrics=[DummyMetric()])
        result = multi_metric(dataset_item={"expected": "ok"}, llm_output="ok")

        assert result.value == pytest.approx(1.0)

    def test_list_returning_metric_raises(self) -> None:
        def metric_returning_list(
            dataset_item: dict[str, Any], llm_output: str
        ) -> list[ScoreResult]:
            _ = dataset_item, llm_output
            return [
                ScoreResult(name="first", value=0.1),
                ScoreResult(name="second", value=0.2),
            ]

        multi_metric = MultiMetricObjective(metrics=[metric_returning_list])

        with pytest.raises(ValueError, match="single ScoreResult"):
            multi_metric(dataset_item={}, llm_output="test output")

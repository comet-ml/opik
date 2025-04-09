from typing import List
from unittest import mock

import pytest

from opik.evaluation import metrics
from opik.evaluation.metrics import score_result


def test_incorrect_constructor_parameters():
    with pytest.raises(ValueError):
        metrics.AggregatedMetric(
            name="test",
            metrics=None,
            aggregator=lambda x: x[0],
        )

    with pytest.raises(ValueError):
        metrics.AggregatedMetric(
            name="test",
            metrics=[],
            aggregator=lambda x: x[0],
        )

    with pytest.raises(ValueError):
        metrics.AggregatedMetric(
            name="test", metrics=[metrics.Equals()], aggregator=None
        )


def test_score():
    first_metric = mock.Mock(spec=metrics.BaseMetric)
    first_metric.score.return_value = score_result.ScoreResult(
        name="first_metric_result", value=0.3
    )

    second_metric = mock.Mock(spec=metrics.BaseMetric)
    second_metric.score.return_value = score_result.ScoreResult(
        name="second_metric_result", value=0.3
    )

    third_metric = mock.Mock(spec=metrics.BaseMetric)
    third_metric.score.return_value = [
        score_result.ScoreResult(name="third_metric_result_1", value=0.1),
        score_result.ScoreResult(name="third_metric_result_2", value=0.3),
    ]
    metrics_list = [first_metric, second_metric, third_metric]

    def aggregator(results: List[score_result.ScoreResult]) -> score_result.ScoreResult:
        value = sum([result.value for result in results])
        return score_result.ScoreResult(name="aggregated_metric_result", value=value)

    agg_metric = metrics.AggregatedMetric(
        name="test", metrics=metrics_list, aggregator=aggregator
    )

    input = {
        "question": "Hello, world!",
    }
    output = {
        "output": "Hello, world!",
    }
    result = agg_metric.score(input=input, output=output)

    # check that score method was called on each metric
    for metric in metrics_list:
        metric.score.assert_called_once_with(input=input, output=output)

    # check that aggregated result has value as a sum of ScoreResults from all metrics
    assert result == score_result.ScoreResult(
        name="aggregated_metric_result", value=1.0
    )

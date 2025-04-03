import pytest

from opik.evaluation.metrics import Equals
from opik.evaluation.metrics.aggregated_metric import AggregatedMetric


def test_incorrect_constructor_parameters():
    with pytest.raises(ValueError):
        AggregatedMetric(
            name="test",
            metrics=None,
            aggregator=lambda x: x[0],
        )

    with pytest.raises(ValueError):
        AggregatedMetric(
            name="test",
            metrics=[],
            aggregator=lambda x: x[0],
        )

    metrics = [Equals()]
    with pytest.raises(ValueError):
        AggregatedMetric(name="test", metrics=metrics, aggregator=None)

from typing import List, Callable, Any

from . import base_metric, score_result


class AggregatedMetric(base_metric.BaseMetric):
    """A metric that aggregates results obtained from a list of provided metrics using specified aggregation function.

    Args:
        name: The name of the metric.
        metrics: A list of concrete metric instances that inherit the `opik.evaluation.base_metric.BaseMetric`.
        aggregator: The aggregation function to use for evaluation.
        track: Whether to track the metric. Defaults to True.
    """

    def __init__(
        self,
        name: str,
        metrics: List[base_metric.BaseMetric],
        aggregator: Callable[
            [List[score_result.ScoreResult]], score_result.ScoreResult
        ],
        track: bool = True,
    ):
        super().__init__(name=name, track=track)
        self.metrics = metrics
        self.aggregator = aggregator

        if self.metrics is None or len(self.metrics) == 0:
            raise ValueError("No metrics provided")

        if aggregator is None:
            raise ValueError("No aggregator provided")

    def score(self, *args: Any, **kwargs: Any) -> score_result.ScoreResult:
        score_results: List[score_result.ScoreResult] = []

        return self.aggregator(score_results)

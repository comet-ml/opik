from typing import Any
from collections.abc import Callable
from opik.evaluation.metrics.score_result import ScoreResult


class MultiMetricObjective:
    def __init__(
        self,
        metrics: list[Callable[[dict[str, Any], str], ScoreResult]],
        weights: list[float] | None = None,
        name: str = "multi_metric_objective",
    ):
        self.metrics = metrics
        self.weights = weights if weights else [1 / len(metrics)] * len(metrics)
        self.__name__ = name

    def __call__(self, dataset_item: dict[str, Any], llm_output: str) -> ScoreResult:
        raw_score_results = []
        weighted_score_value = 0

        for metric, weight in zip(self.metrics, self.weights):
            score_result = metric(dataset_item, llm_output)
            raw_score_results.append(score_result)
            weighted_score_value += score_result.value * weight

        aggregated_score_result = ScoreResult(
            name=self.__name__,
            value=weighted_score_value,
            metadata={"raw_score_results": raw_score_results},
        )

        # Important: we return the aggregated score result first
        return aggregated_score_result

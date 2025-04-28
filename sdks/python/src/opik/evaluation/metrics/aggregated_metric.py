from typing import List, Callable, Any, Dict, Optional

from .. import types as evaluation_types

from . import arguments_helpers, arguments_validator, base_metric, score_result


class AggregatedMetric(
    base_metric.BaseMetric, arguments_validator.ScoreArgumentsValidator
):
    """A metric that aggregates results obtained from a list of provided metrics using specified aggregation function.

    Args:
        name: The name of the metric.
        metrics: A list of concrete metric instances that inherit the `opik.evaluation.base_metric.BaseMetric`.
        aggregator: The aggregation function to use for evaluation.
        track: Whether to track the metric. Defaults to True.
        project_name: Optional project name to track the metric in for the cases when there are no parent span/trace to inherit project name from.
    """

    def __init__(
        self,
        name: str,
        metrics: List[base_metric.BaseMetric],
        aggregator: Callable[
            [List[score_result.ScoreResult]], score_result.ScoreResult
        ],
        track: bool = True,
        project_name: Optional[str] = None,
    ):
        super().__init__(name=name, track=track, project_name=project_name)
        self.metrics = metrics
        self.aggregator = aggregator

        if self.metrics is None or len(self.metrics) == 0:
            raise ValueError("No metrics provided")

        if aggregator is None:
            raise ValueError("No aggregator provided")

    def score(self, **kwargs: Any) -> score_result.ScoreResult:
        score_results: List[score_result.ScoreResult] = []
        for metric in self.metrics:
            metric_result = metric.score(**kwargs)
            if isinstance(metric_result, list):
                score_results.extend(metric_result)
            else:
                score_results.append(metric_result)

        return self.aggregator(score_results)

    def validate_score_arguments(
        self,
        score_kwargs: Dict[str, Any],
        key_mapping: Optional[evaluation_types.ScoringKeyMappingType],
    ) -> None:
        for metric in self.metrics:
            arguments_helpers.raise_if_score_arguments_are_missing(
                score_function=metric.score,
                score_name=metric.name,
                kwargs=score_kwargs,
                scoring_key_mapping=key_mapping,
            )

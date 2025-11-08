from typing import List, Callable, Any, Dict, Optional

from .. import types as evaluation_types

from . import arguments_helpers, arguments_validator, base_metric, score_result


class AggregatedMetric(
    base_metric.BaseMetric, arguments_validator.ScoreArgumentsValidator
):
    """
    Combine the output of multiple metrics into a single aggregated ``ScoreResult``.

    Each metric in ``metrics`` is executed with the provided scoring kwargs, then the
    ``aggregator`` callback decides how to merge the individual results. This is
    handy for building ensembles such as min/max, weighted averages, or custom
    pass/fail checks without re-implementing the metrics themselves.

    Args:
        name: Display name for the aggregated metric result.
        metrics: Ordered list of metric instances that should be executed.
        aggregator: Callable receiving the list of ``ScoreResult`` objects and
            returning the final aggregated ``ScoreResult``.
        track: Whether to automatically track the metric in Opik. Defaults to
            ``True``.
        project_name: Optional tracking project used when no parent context exists.

    Example:
        >>> from opik.evaluation.metrics import AggregatedMetric, Contains, RegexMatch
        >>> metrics = [Contains(track=False), RegexMatch(pattern=r"\\d+", track=False)]
        >>> from opik.evaluation.metrics import score_result
        >>> def combine(results):
        ...     score = sum(result.value for result in results) / len(results)
        ...     return score_result.ScoreResult(
        ...         name="combined_contains_regex",
        ...         value=score,
        ...         reason="Average of contains and regex checks",
        ...     )
        >>> metric = AggregatedMetric(
        ...     name="combined_contains_regex",
        ...     metrics=metrics,
        ...     aggregator=combine,
        ... )
        >>> response = "Order number 12345 confirmed"
        >>> result = metric.score(output=response, reference="order")
        >>> float(result.value)  # doctest: +SKIP
        1.0
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

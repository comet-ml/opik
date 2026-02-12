from collections.abc import Callable
from typing import Any, TypeAlias, cast

from opik.evaluation.metrics import base_metric
from opik.evaluation.metrics.score_result import ScoreResult

MetricCallable: TypeAlias = Callable[
    [dict[str, Any], str], ScoreResult | float | list[ScoreResult]
]
MetricLike: TypeAlias = MetricCallable | base_metric.BaseMetric
MetricType: TypeAlias = MetricCallable
ReasonBuilder: TypeAlias = Callable[[list[ScoreResult], list[float], float], str | None]


class MultiMetricObjective:
    """Combine multiple metrics into a single weighted composite score."""

    def __init__(
        self,
        metrics: list[MetricLike],
        weights: list[float] | None = None,
        name: str = "multi_metric_objective",
        reason: str | None = None,
        reason_builder: ReasonBuilder | None = None,
    ):
        """Initialize a composite metric with optional reason overrides.

        Args:
            metrics: Metric callables or BaseMetric instances.
            weights: Optional weights aligned to metrics; defaults to equal weights.
            name: Name for the aggregated ScoreResult.
            reason: Optional static reason string override.
            reason_builder: Optional callback to build a reason from subscores.
        """
        if not metrics:
            raise ValueError("MultiMetricObjective requires at least one metric.")
        if weights is not None and len(weights) != len(metrics):
            raise ValueError("Weights length must match metrics length.")

        self.metrics = list(metrics)
        self.weights = (
            list(weights)
            if weights is not None
            else [1.0 / len(metrics)] * len(metrics)
        )
        self.__name__ = name
        self._reason = reason
        self._reason_builder = reason_builder

    def __call__(
        self,
        dataset_item: dict[str, Any],
        llm_output: str,
        *,
        reason: str | None = None,
        reason_builder: ReasonBuilder | None = None,
    ) -> ScoreResult:
        """Score a dataset item and return the weighted composite ScoreResult.

        Args:
            dataset_item: Dataset row passed to metric callables/BaseMetrics.
            llm_output: Model output to evaluate.
            reason: Optional per-call reason override.
            reason_builder: Optional per-call reason builder override.
        """
        raw_score_results: list[ScoreResult] = []
        weighted_score_value = 0.0
        scoring_failed = False

        for metric, weight in zip(self.metrics, self.weights):
            score_result = self._score_metric(metric, dataset_item, llm_output)
            weighted_score_value += score_result.value * weight
            scoring_failed = scoring_failed or score_result.scoring_failed
            raw_score_results.append(score_result)

        aggregated_reason = self._build_reason(
            raw_score_results,
            weighted_score_value,
            reason_override=reason,
            reason_builder_override=reason_builder,
        )

        return ScoreResult(
            name=self.__name__,
            value=weighted_score_value,
            reason=aggregated_reason,
            metadata={"raw_score_results": raw_score_results},
            scoring_failed=scoring_failed,
        )

    def _build_reason(
        self,
        raw_score_results: list[ScoreResult],
        weighted_score_value: float,
        *,
        reason_override: str | None,
        reason_builder_override: ReasonBuilder | None,
    ) -> str:
        """Resolve the final reason string using overrides or builder fallback."""
        if reason_override is None:
            reason_override = self._reason
        if reason_override is not None:
            return reason_override

        reason_builder = reason_builder_override or self._reason_builder
        if reason_builder is None:
            return self._default_reason(raw_score_results)

        custom_reason = reason_builder(
            raw_score_results, self.weights, weighted_score_value
        )
        if custom_reason is None or not custom_reason.strip():
            return self._default_reason(raw_score_results)
        return custom_reason

    def _default_reason(self, raw_score_results: list[ScoreResult]) -> str:
        """Build the default reason string from subscores and weights."""
        if not raw_score_results:
            return f"{self.__name__} composite evaluation"

        reason_parts = []
        for score_result, weight in zip(raw_score_results, self.weights):
            part = f"{score_result.name}={score_result.value:.3f} (w={weight:.2f})"
            if score_result.scoring_failed:
                part = f"{part} [FAILED]"
            reason_parts.append(part)

        return " | ".join(reason_parts)

    def _score_metric(
        self,
        metric: MetricLike,
        dataset_item: dict[str, Any],
        llm_output: str,
    ) -> ScoreResult:
        """Evaluate a single metric and normalize it to a ScoreResult."""
        try:
            metric_result = self._call_metric(metric, dataset_item, llm_output)
            return self._normalize_metric_result(metric, metric_result)
        except Exception as exception:
            return ScoreResult(
                name=self._metric_name(metric),
                value=0.0,
                reason=f"Metric execution failed: {exception}",
                scoring_failed=True,
                metadata={
                    "_error_type": type(exception).__name__,
                    "_error_message": str(exception),
                },
            )

    def _call_metric(
        self,
        metric: MetricLike,
        dataset_item: dict[str, Any],
        llm_output: str,
    ) -> ScoreResult | float | list[ScoreResult]:
        """Dispatch to BaseMetric.score or to the metric callable."""
        if isinstance(metric, base_metric.BaseMetric):
            scoring_inputs = {**dataset_item, "output": llm_output}
            return metric.score(**scoring_inputs)
        metric_callable = cast(MetricCallable, metric)
        return metric_callable(dataset_item, llm_output)

    def _normalize_metric_result(
        self,
        metric: MetricLike,
        metric_result: ScoreResult | float | list[ScoreResult],
    ) -> ScoreResult:
        """Normalize metric outputs to a single ScoreResult."""
        if isinstance(metric_result, list):
            if len(metric_result) != 1:
                raise ValueError(
                    "MultiMetricObjective metrics must return a single ScoreResult. "
                    "Wrap list-returning metrics to select or aggregate one result."
                )
            metric_result = metric_result[0]

        if isinstance(metric_result, ScoreResult):
            return metric_result

        return ScoreResult(name=self._metric_name(metric), value=float(metric_result))

    @staticmethod
    def _metric_name(metric: MetricLike) -> str:
        """Resolve the display name for a metric."""
        name = getattr(metric, "name", None)
        if isinstance(name, str) and name:
            return name
        func_name = getattr(metric, "__name__", None)
        if isinstance(func_name, str) and func_name:
            return func_name
        return type(metric).__name__

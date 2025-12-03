from typing import Any, Protocol, cast
from opik.evaluation.metrics import score_result
from opik.message_processing.emulation import models as emulation_models
from . import helpers
import opik.exceptions


class MetricFunction(Protocol):
    def __call__(
        self, dataset_item: dict[str, Any], llm_output: str
    ) -> score_result.ScoreResult: ...


class MetricWithTaskSpanFunction(Protocol):
    def __call__(
        self,
        dataset_item: dict[str, Any],
        llm_output: str,
        task_span: emulation_models.SpanModel | None,
    ) -> score_result.ScoreResult: ...


MetricType = MetricFunction | MetricWithTaskSpanFunction


class MultiMetricObjective:
    def __init__(
        self,
        metrics: list[MetricFunction | MetricWithTaskSpanFunction],
        weights: list[float] | None = None,
        name: str = "multi_metric_objective",
    ):
        self.__name__ = name
        self.metrics = metrics

        if weights is not None and len(metrics) != len(weights):
            raise ValueError("metrics and weights must have the same length")

        self.weights = weights if weights else [1 / len(metrics)] * len(metrics)

        self._needs_task_span = any(
            helpers.has_task_span_parameter(metric) for metric in self.metrics
        )

    @property
    def needs_task_span(self) -> bool:
        return self._needs_task_span

    def __call__(
        self,
        dataset_item: dict[str, Any],
        llm_output: str,
        task_span: emulation_models.SpanModel | None = None,
    ) -> score_result.ScoreResult:
        try:
            raw_score_results = []
            weighted_score_value = 0

            for metric, weight in zip(self.metrics, self.weights):
                if helpers.has_task_span_parameter(metric) and task_span is not None:
                    metric_with_span = cast(MetricWithTaskSpanFunction, metric)
                    score_result_ = metric_with_span(
                        dataset_item=dataset_item,
                        llm_output=llm_output,
                        task_span=task_span,
                    )
                else:
                    metric_without_span = cast(MetricFunction, metric)
                    score_result_ = metric_without_span(
                        dataset_item=dataset_item, llm_output=llm_output
                    )
                raw_score_results.append(score_result_)
                weighted_score_value += score_result_.value * weight

            aggregated_score_result = score_result.ScoreResult(
                name=self.__name__,
                value=weighted_score_value,
                metadata={"raw_score_results": raw_score_results},
            )

            return aggregated_score_result
        except opik.exceptions.MetricComputationError:
            raise
        except Exception as exception:
            raise opik.exceptions.MetricComputationError(
                f"Failed to compute {self.__name__}"
            ) from exception

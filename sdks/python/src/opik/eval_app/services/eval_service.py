"""Evaluation service for the eval app."""

import logging
from typing import Any, Dict, List, Optional

import opik
import opik.api_objects.opik_client
from opik.evaluation.metrics import base_metric
from opik.rest_api.types import trace_public
from opik.types import FeedbackScoreDict

from .. import exceptions
from .. import schemas
from ..metrics import MetricInfo, MetricsRegistry, get_default_registry

LOGGER = logging.getLogger(__name__)


class EvalService:
    """Service for running metric evaluations on traces."""

    def __init__(self, registry: MetricsRegistry) -> None:
        self._registry = registry

    def list_metrics(self) -> List[MetricInfo]:
        """List all available metrics with their descriptors."""
        return self._registry.list_all()

    def evaluate_trace(
        self,
        request: schemas.EvaluationRequest,
    ) -> schemas.EvaluationAcceptedResponse:
        """
        Evaluate a trace using specified metrics and log feedback scores.

        Args:
            request: Evaluation request containing trace_id, metrics, and field mapping.

        Returns:
            EvaluationAcceptedResponse confirming the request was processed.

        Raises:
            TraceNotFoundError: If the trace is not found.
            UnknownMetricError: If a requested metric is not in the registry.
            MetricInstantiationError: If a metric cannot be instantiated.
            InvalidFieldMappingError: If field mapping is invalid.
            EvaluationError: If evaluation fails.
        """
        opik_client = opik.api_objects.opik_client.get_client_cached()

        trace = self._fetch_trace(opik_client, request.trace_id)
        project_name = request.project_name or trace.project_id

        scoring_metrics = self._instantiate_metrics(request.metrics)
        metric_inputs = self._extract_metric_inputs(trace, request.field_mapping)

        self._run_evaluation_and_log_scores(
            opik_client=opik_client,
            trace_id=request.trace_id,
            project_name=project_name,
            scoring_metrics=scoring_metrics,
            metric_inputs=metric_inputs,
        )

        return schemas.EvaluationAcceptedResponse(
            trace_id=request.trace_id,
            metrics_count=len(scoring_metrics),
        )

    def _fetch_trace(
        self, opik_client: opik.Opik, trace_id: str
    ) -> trace_public.TracePublic:
        """Fetch trace data from Opik."""
        try:
            return opik_client.get_trace_content(trace_id)
        except Exception as e:
            LOGGER.error("Failed to fetch trace %s: %s", trace_id, str(e))
            raise exceptions.TraceNotFoundError(trace_id)

    def _instantiate_metrics(
        self, metric_configs: List[schemas.MetricConfig]
    ) -> List[base_metric.BaseMetric]:
        """Instantiate metrics from configurations."""
        scoring_metrics: List[base_metric.BaseMetric] = []

        for config in metric_configs:
            metric_class = self._registry.get_metric_class(config.name)
            if metric_class is None:
                raise exceptions.UnknownMetricError(config.name)

            try:
                init_args = {"track": False, **config.init_args}
                metric_instance = metric_class(**init_args)
                scoring_metrics.append(metric_instance)
                LOGGER.debug("Instantiated metric: %s", config.name)
            except Exception as e:
                LOGGER.error(
                    "Failed to instantiate metric %s: %s",
                    config.name,
                    str(e),
                    exc_info=True,
                )
                raise exceptions.MetricInstantiationError(config.name, str(e))

        return scoring_metrics

    def _extract_metric_inputs(
        self,
        trace: trace_public.TracePublic,
        field_mapping: schemas.TraceFieldMapping,
    ) -> Dict[str, Any]:
        """Extract metric inputs from trace based on field mapping."""
        metric_inputs: Dict[str, Any] = {}

        for metric_arg, trace_field_path in field_mapping.mapping.items():
            value = self._get_trace_field_value(trace, trace_field_path)
            metric_inputs[metric_arg] = value

        return metric_inputs

    def _get_trace_field_value(
        self, trace: trace_public.TracePublic, field_path: str
    ) -> Any:
        """Get a value from trace using dot notation path."""
        parts = field_path.split(".")
        root_field = parts[0]

        trace_field_map = {
            "input": trace.input,
            "output": trace.output,
            "metadata": trace.metadata,
            "name": trace.name,
            "tags": trace.tags,
        }

        if root_field not in trace_field_map:
            raise exceptions.InvalidFieldMappingError(
                field_path,
                f"Unknown trace field '{root_field}'. "
                f"Supported fields: {list(trace_field_map.keys())}",
            )

        value = trace_field_map[root_field]

        for part in parts[1:]:
            if value is None:
                return None
            if isinstance(value, dict):
                value = value.get(part)
            else:
                raise exceptions.InvalidFieldMappingError(
                    field_path,
                    f"Cannot access '{part}' on non-dict value",
                )

        return value

    def _run_evaluation_and_log_scores(
        self,
        opik_client: opik.Opik,
        trace_id: str,
        project_name: Optional[str],
        scoring_metrics: List[base_metric.BaseMetric],
        metric_inputs: Dict[str, Any],
    ) -> None:
        """Run evaluation and log feedback scores to the trace."""
        try:
            LOGGER.info(
                "Running evaluation on trace %s with %d metrics",
                trace_id,
                len(scoring_metrics),
            )

            feedback_scores: List[FeedbackScoreDict] = []

            for metric in scoring_metrics:
                try:
                    result = metric.score(**metric_inputs)

                    # Handle both single result and list of results
                    if isinstance(result, list):
                        for score_result in result:
                            feedback_scores.append(
                                FeedbackScoreDict(
                                    id=trace_id,
                                    name=score_result.name,
                                    value=score_result.value,
                                    reason=getattr(score_result, "reason", None),
                                )
                            )
                            LOGGER.debug(
                                "Metric %s scored: %s",
                                score_result.name,
                                score_result.value,
                            )
                    else:
                        feedback_scores.append(
                            FeedbackScoreDict(
                                id=trace_id,
                                name=result.name,
                                value=result.value,
                                reason=getattr(result, "reason", None),
                            )
                        )
                        LOGGER.debug("Metric %s scored: %s", result.name, result.value)
                except Exception as e:
                    LOGGER.warning(
                        "Metric %s failed to score: %s",
                        metric.name,
                        str(e),
                    )

            if feedback_scores:
                opik_client.log_traces_feedback_scores(
                    scores=feedback_scores,
                    project_name=project_name,
                )
                opik_client.flush()
                LOGGER.info(
                    "Logged %d feedback scores for trace %s",
                    len(feedback_scores),
                    trace_id,
                )

        except Exception as e:
            LOGGER.error("Evaluation failed: %s", str(e), exc_info=True)
            raise exceptions.EvaluationError(str(e))


def create_service(
    registry: Optional[MetricsRegistry] = None,
) -> EvalService:
    """
    Create an EvalService instance.

    Args:
        registry: Optional metrics registry. If not provided, uses the default registry.

    Returns:
        Configured EvalService instance.
    """
    if registry is None:
        registry = get_default_registry()
    return EvalService(registry=registry)

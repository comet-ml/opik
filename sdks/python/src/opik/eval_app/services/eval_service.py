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


def _identity_task(item: Dict[str, Any]) -> Dict[str, Any]:
    """Identity function - returns the input unchanged."""
    return item


class EvalService:
    """Service for running metric evaluations on traces."""

    def __init__(self, registry: MetricsRegistry) -> None:
        self._registry = registry

    def list_metrics(self) -> List[MetricInfo]:
        """List all available metrics with their descriptors."""
        return self._registry.list_all()

    def evaluate_trace(
        self,
        trace_id: str,
        request: schemas.EvaluationRequest,
    ) -> schemas.EvaluationAcceptedResponse:
        """Evaluate a trace with the specified metrics."""
        opik_client = opik.api_objects.opik_client.get_client_cached()

        trace = self._fetch_trace(opik_client, trace_id)

        self._run_metrics_and_log_scores(
            opik_client=opik_client,
            trace_id=trace_id,
            trace=trace,
            metric_configs=request.metrics,
        )

        return schemas.EvaluationAcceptedResponse(
            trace_id=trace_id,
            metrics_count=len(request.metrics),
        )

    def _fetch_trace(
        self, opik_client: opik.Opik, trace_id: str
    ) -> trace_public.TracePublic:
        """Fetch trace data from the backend."""
        try:
            trace = opik_client._rest_client.traces.get_trace_by_id(id=trace_id)
            return trace
        except Exception as e:
            LOGGER.error("Failed to fetch trace %s: %s", trace_id, e)
            raise exceptions.TraceNotFoundError(trace_id) from e

    def _instantiate_metric(
        self, config: schemas.MetricEvaluationConfig
    ) -> base_metric.BaseMetric:
        """Instantiate a metric from configuration."""
        metric_class = self._registry.get_metric_class(config.metric_name)
        if metric_class is None:
            raise exceptions.UnknownMetricError(config.metric_name)

        try:
            return metric_class(**config.init_args)
        except Exception as e:
            raise exceptions.MetricInstantiationError(config.metric_name, str(e)) from e

    def _extract_trace_data(self, trace: trace_public.TracePublic) -> Dict[str, Any]:
        """Extract trace data as a flat dictionary for scoring."""
        data: Dict[str, Any] = {}

        if trace.input is not None:
            if isinstance(trace.input, dict):
                for key, value in trace.input.items():
                    data[f"input.{key}"] = value
            data["input"] = trace.input

        if trace.output is not None:
            if isinstance(trace.output, dict):
                for key, value in trace.output.items():
                    data[f"output.{key}"] = value
            data["output"] = trace.output

        if trace.metadata is not None:
            if isinstance(trace.metadata, dict):
                for key, value in trace.metadata.items():
                    data[f"metadata.{key}"] = value
                    # Special handling for dataset_item_data
                    if key == "dataset_item_data" and isinstance(value, dict):
                        for dk, dv in value.items():
                            data[f"dataset_item_data.{dk}"] = dv
            data["metadata"] = trace.metadata

        return data

    def _build_scoring_key_mapping(
        self, config: schemas.MetricEvaluationConfig
    ) -> Dict[str, str]:
        """Build scoring key mapping from metric config arguments."""
        return dict(config.arguments)

    def _run_metrics_and_log_scores(
        self,
        opik_client: opik.Opik,
        trace_id: str,
        trace: trace_public.TracePublic,
        metric_configs: List[schemas.MetricEvaluationConfig],
    ) -> None:
        """Run all metrics using evaluate_on_dict_items and log scores to the trace."""
        trace_data = self._extract_trace_data(trace)
        feedback_scores: List[FeedbackScoreDict] = []

        for config in metric_configs:
            try:
                metric = self._instantiate_metric(config)
                scoring_key_mapping = self._build_scoring_key_mapping(config)

                # Use evaluate_on_dict_items with identity task
                result = opik.evaluate_on_dict_items(
                    items=[trace_data],
                    task=_identity_task,
                    scoring_metrics=[metric],
                    scoring_key_mapping=scoring_key_mapping,
                    scoring_threads=1,
                    verbose=0,
                )

                # Extract scores from results
                for test_result in result.test_results:
                    for score_result in test_result.score_results:
                        score_name = config.name if config.name else score_result.name
                        feedback_scores.append({
                            "name": score_name,
                            "value": score_result.value,
                            "reason": score_result.reason,
                        })
                        LOGGER.info(
                            "Metric %s scored: %s = %s",
                            metric.__class__.__name__,
                            score_name,
                            score_result.value,
                        )
            except Exception as e:
                LOGGER.error("Metric %s failed: %s", config.metric_name, e)
                continue

        if feedback_scores:
            try:
                scores_with_trace_id: List[FeedbackScoreDict] = [
                    {
                        "id": trace_id,
                        "name": score["name"],
                        "value": score["value"],
                        "reason": score.get("reason"),
                    }
                    for score in feedback_scores
                ]
                opik_client.log_traces_feedback_scores(scores_with_trace_id)
                LOGGER.info(
                    "Logged %d feedback scores for trace %s",
                    len(feedback_scores),
                    trace_id,
                )
            except Exception as e:
                LOGGER.error(
                    "Failed to log feedback scores for trace %s: %s", trace_id, e
                )


def create_service(
    registry: Optional[MetricsRegistry] = None,
) -> EvalService:
    """Create an EvalService instance."""
    if registry is None:
        registry = get_default_registry()
    return EvalService(registry)

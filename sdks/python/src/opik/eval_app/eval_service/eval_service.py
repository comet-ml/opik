"""Evaluation service for the eval app."""

import concurrent.futures
import logging
import os
from typing import Any, Dict, List, Optional

import opik
import opik.api_objects.opik_client
from opik import types as opik_types
from opik.evaluation.metrics import base_metric
from opik.rest_api.types import trace_public

from .. import exceptions
from .. import schemas
from . import metrics
from . import trace_data_extractor

LOGGER = logging.getLogger(__name__)

# Default number of threads for parallel metric execution
DEFAULT_METRIC_THREADS = 4

# Shared thread pool executor (created lazily per process)
_metric_executor: Optional[concurrent.futures.ThreadPoolExecutor] = None


def _get_metric_executor() -> concurrent.futures.ThreadPoolExecutor:
    """Get or create the shared thread pool executor for metric execution."""
    global _metric_executor
    if _metric_executor is None:
        num_threads = int(
            os.environ.get("OPIK_EVAL_APP_METRIC_THREADS", DEFAULT_METRIC_THREADS)
        )
        _metric_executor = concurrent.futures.ThreadPoolExecutor(
            max_workers=num_threads,
            thread_name_prefix="metric_worker",
        )
        LOGGER.info("Created metric executor with %d threads", num_threads)
    return _metric_executor


class EvalService:
    """Service for running metric evaluations on traces."""

    def __init__(self, registry: metrics.MetricsRegistry) -> None:
        self._registry = registry

    def list_metrics(self) -> List[metrics.MetricInfo]:
        """List all available metrics with their descriptors."""
        return self._registry.list_all()

    def evaluate_trace(
        self,
        trace_id: str,
        request: schemas.EvaluationRequest,
    ) -> schemas.EvaluationAcceptedResponse:
        """Evaluate a trace with the specified metrics."""
        LOGGER.info("Starting evaluation for trace %s with %d metrics",
                    trace_id, len(request.metrics))

        opik_client = opik.api_objects.opik_client.get_client_cached()
        trace = self._fetch_trace(opik_client, trace_id)

        self._run_metrics_and_log_scores(
            opik_client=opik_client,
            trace_id=trace_id,
            trace=trace,
            metric_configs=request.metrics,
        )

        LOGGER.info("Completed evaluation for trace %s", trace_id)

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
            LOGGER.debug(
                "Fetched trace %s: input_type=%s, output_type=%s, "
                "input_keys=%s, output_keys=%s",
                trace_id,
                type(trace.input).__name__ if trace.input else None,
                type(trace.output).__name__ if trace.output else None,
                list(trace.input.keys()) if isinstance(trace.input, dict) else None,
                list(trace.output.keys()) if isinstance(trace.output, dict) else None,
            )
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

    def _run_single_metric(
        self,
        config: schemas.MetricEvaluationConfig,
        trace_dict: Dict[str, Any],
    ) -> List[Dict[str, Any]]:
        """Run a single metric and return score results."""
        metric = self._instantiate_metric(config)
        metric_inputs = trace_data_extractor.build_metric_inputs(
            trace_dict, config.arguments
        )
        LOGGER.debug(
            "Running metric %s with inputs: %s",
            config.metric_name,
            {
                k: v[:100] if isinstance(v, str) and len(v) > 100 else v
                for k, v in metric_inputs.items()
            },
        )
        result = metric.score(**metric_inputs)

        # Handle both single result and list of results
        results = result if isinstance(result, list) else [result]

        scores = []
        for score_result in results:
            score_name = config.name if config.name else score_result.name
            scores.append(
                {
                    "name": score_name,
                    "value": score_result.value,
                    "reason": score_result.reason,
                }
            )
            LOGGER.info(
                "Metric %s scored: %s = %s",
                metric.__class__.__name__,
                score_name,
                score_result.value,
            )

        return scores

    def _run_metrics_and_log_scores(
        self,
        opik_client: opik.Opik,
        trace_id: str,
        trace: trace_public.TracePublic,
        metric_configs: List[schemas.MetricEvaluationConfig],
    ) -> None:
        """Run all metrics in parallel and log feedback scores to the trace."""
        feedback_scores: List[opik_types.FeedbackScoreDict] = []

        # Convert trace to dict once for all metrics
        trace_dict = trace_data_extractor.trace_to_dict(trace)

        # Run metrics in parallel using shared thread pool
        executor = _get_metric_executor()
        futures = {
            executor.submit(self._run_single_metric, config, trace_dict): config
            for config in metric_configs
        }

        for future in concurrent.futures.as_completed(futures):
            config = futures[future]
            try:
                scores = future.result()
                feedback_scores.extend(scores)
            except Exception as e:
                LOGGER.error("Metric %s failed: %s", config.metric_name, e)
                continue

        if feedback_scores:
            try:
                scores_with_trace_id: List[opik_types.FeedbackScoreDict] = [
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
                    "Failed to log feedback scores for trace %s: %s",
                    trace_id,
                    e,
                )


def create_service(
    registry: Optional[metrics.MetricsRegistry] = None,
) -> EvalService:
    """Create an EvalService instance."""
    if registry is None:
        registry = metrics.get_default_registry()
    return EvalService(registry)

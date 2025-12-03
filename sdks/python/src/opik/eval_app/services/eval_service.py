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
        trace_id: str,
        request: schemas.EvaluationRequest,
    ) -> schemas.EvaluationAcceptedResponse:
        """Evaluate a trace with the specified rules."""
        opik_client = opik.api_objects.opik_client.get_client_cached()

        trace = self._fetch_trace(opik_client, trace_id)

        self._run_rules_and_log_scores(
            opik_client=opik_client,
            trace_id=trace_id,
            trace=trace,
            rules=request.rules,
        )

        return schemas.EvaluationAcceptedResponse(
            trace_id=trace_id,
            rules_count=len(request.rules),
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
        self, rule: schemas.LocalEvaluationRuleConfig
    ) -> base_metric.BaseMetric:
        """Instantiate a metric from a rule configuration."""
        metric_class = self._registry.get_metric_class(rule.metric_name)
        if metric_class is None:
            raise exceptions.UnknownMetricError(rule.metric_name)

        try:
            return metric_class(**rule.init_args)
        except Exception as e:
            raise exceptions.MetricInstantiationError(rule.metric_name, str(e)) from e

    def _extract_metric_inputs(
        self,
        trace: trace_public.TracePublic,
        arguments: Dict[str, str],
    ) -> Dict[str, Any]:
        """Extract metric inputs from trace using argument mapping."""
        metric_inputs: Dict[str, Any] = {}

        for arg_name, field_path in arguments.items():
            try:
                value = self._get_trace_field_value(trace, field_path)
                metric_inputs[arg_name] = value
            except Exception as e:
                raise exceptions.InvalidFieldMappingError(field_path, str(e)) from e

        return metric_inputs

    def _get_trace_field_value(
        self, trace: trace_public.TracePublic, field_path: str
    ) -> Any:
        """Get a value from a trace using dot notation path.

        Special handling:
        - 'dataset_item_data.*' is mapped to 'metadata.dataset_item_data.*'
          since the frontend uses this shorthand
        """
        # Handle special dataset_item_data prefix
        if field_path.startswith("dataset_item_data."):
            field_path = "metadata." + field_path

        parts = field_path.split(".")
        current = trace

        for part in parts:
            if hasattr(current, part):
                current = getattr(current, part)
            elif isinstance(current, dict) and part in current:
                current = current[part]
            else:
                # Return None for missing fields instead of raising error
                return None

        # Convert to appropriate Python type
        if current is None:
            return None
        if isinstance(current, (str, int, float, bool, list, dict)):
            return current
        # Convert Pydantic models or other objects to dict
        if hasattr(current, "model_dump"):
            return current.model_dump()
        if hasattr(current, "dict"):
            return current.dict()
        return str(current)

    def _run_rules_and_log_scores(
        self,
        opik_client: opik.Opik,
        trace_id: str,
        trace: trace_public.TracePublic,
        rules: List[schemas.LocalEvaluationRuleConfig],
    ) -> None:
        """Run all rules and log feedback scores to the trace."""
        feedback_scores: List[FeedbackScoreDict] = []

        for rule in rules:
            try:
                # Instantiate metric
                metric = self._instantiate_metric(rule)
                metric_class_name = metric.__class__.__name__

                # Use custom name if provided, otherwise use the metric's score name
                custom_name = rule.name

                # Extract inputs from trace using the rule's argument mapping
                metric_inputs = self._extract_metric_inputs(trace, rule.arguments)

                # Run the metric
                result = metric.score(**metric_inputs)

                # Handle both single result and list of results
                if isinstance(result, list):
                    results = result
                else:
                    results = [result]

                for score_result in results:
                    # Use custom name if provided, otherwise use the score result name
                    score_name = custom_name if custom_name else score_result.name
                    feedback_score: FeedbackScoreDict = {
                        "name": score_name,
                        "value": score_result.value,
                        "reason": score_result.reason,
                    }
                    feedback_scores.append(feedback_score)
                    LOGGER.info(
                        "Metric %s scored: %s = %s",
                        metric_class_name,
                        score_name,
                        score_result.value,
                    )
            except Exception as e:
                LOGGER.error(
                    "Metric %s failed: %s", rule.metric_name, e
                )
                # Continue with other rules even if one fails
                continue

        if feedback_scores:
            try:
                # Each score needs the trace_id as the 'id' key
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

import inspect
import logging
from typing import List, Dict, Any, Optional, Callable

import opik.exceptions as exceptions
import opik.logging_messages as logging_messages
from opik.evaluation.metrics import base_metric, score_result, arguments_validator
from opik.evaluation.scorers import scorer_wrapper_metric
from opik.evaluation.types import ScoringKeyMappingType
from opik.message_processing.emulation import models

from . import exception_analyzer


LOGGER = logging.getLogger(__name__)

EVALUATION_SPAN_PARAMETER_NAME = "task_span"


def _has_evaluation_span_parameter(func: Callable) -> bool:
    """Check if a scoring function expects the task_span parameter."""
    try:
        sig = inspect.signature(func)
        return EVALUATION_SPAN_PARAMETER_NAME in sig.parameters
    except (ValueError, TypeError):
        return False


def _compute_metric_scores(
    scoring_metrics: List[base_metric.BaseMetric],
    score_kwargs: Dict[str, Any],
    scoring_key_mapping: Optional[ScoringKeyMappingType],
    dataset_item_content: Dict[str, Any],
    task_output: Dict[str, Any],
) -> List[score_result.ScoreResult]:
    """
    Compute scores using given metrics.

    Args:
        scoring_metrics: List of metrics to compute
        score_kwargs: Keyword arguments to pass to metric.score()
        scoring_key_mapping: Optional mapping for renaming score arguments
        dataset_item_content: Dataset item content
        task_output: Task output

    Returns:
        List of computed score results
    """
    score_results: List[score_result.ScoreResult] = []

    for metric in scoring_metrics:
        try:
            LOGGER.debug("Metric %s score started", metric.name)

            if isinstance(metric, scorer_wrapper_metric.ScorerWrapperMetric):
                # ScorerWrapperMetric uses original dataset item and task output without mappings
                if (
                    task_span := score_kwargs.get(EVALUATION_SPAN_PARAMETER_NAME)
                ) is not None:
                    result = metric.score(
                        dataset_item=dataset_item_content,
                        task_outputs=task_output,
                        task_span=task_span,
                    )
                else:
                    result = metric.score(
                        dataset_item=dataset_item_content,
                        task_outputs=task_output,
                    )
            else:
                arguments_validator.validate_score_arguments(
                    metric=metric,
                    kwargs=score_kwargs,
                    scoring_key_mapping=scoring_key_mapping,
                )
                result = metric.score(**score_kwargs)

            LOGGER.debug("Metric %s score ended", metric.name)

            if isinstance(result, list):
                score_results += result
            else:
                score_results.append(result)

        except exceptions.ScoreMethodMissingArguments:
            raise
        except Exception as exception:
            LOGGER.error(
                "Failed to compute metric %s. Score result will be marked as failed.",
                metric.name,
                exc_info=True,
            )

            if exception_analyzer.is_llm_provider_rate_limit_error(exception):
                LOGGER.error(
                    logging_messages.LLM_PROVIDER_RATE_LIMIT_ERROR_DETECTED_IN_EVALUATE_FUNCTION
                )

            score_results.append(
                score_result.ScoreResult(
                    name=metric.name,
                    value=0.0,
                    reason=str(exception),
                    scoring_failed=True,
                )
            )

    return score_results


class MetricsEvaluator:
    """
    Handles metric computation and scoring.

    Separates metrics into:
    - Regular metrics: Score based on inputs/outputs
    - Task span metrics: Score based on LLM call metadata (tokens, latency, etc)
    """

    def __init__(
        self,
        scoring_metrics: List[base_metric.BaseMetric],
        scoring_key_mapping: Optional[ScoringKeyMappingType],
    ):
        self._scoring_key_mapping = scoring_key_mapping
        self._regular_metrics: List[base_metric.BaseMetric] = []
        self._task_span_metrics: List[base_metric.BaseMetric] = []

        self._analyze_metrics(scoring_metrics)

    @property
    def has_task_span_metrics(self) -> bool:
        """Check if any task span scoring metrics are configured."""
        return len(self._task_span_metrics) > 0

    @property
    def task_span_metrics(self) -> List[base_metric.BaseMetric]:
        """Get list of task span scoring metrics."""
        return self._task_span_metrics

    @property
    def regular_metrics(self) -> List[base_metric.BaseMetric]:
        """Get list of regular scoring metrics."""
        return self._regular_metrics

    def _analyze_metrics(
        self,
        scoring_metrics: List[base_metric.BaseMetric],
    ) -> None:
        """Separate metrics into regular and task-span categories."""
        for metric in scoring_metrics:
            if _has_evaluation_span_parameter(metric.score):
                self._task_span_metrics.append(metric)
            else:
                self._regular_metrics.append(metric)

        if self.has_task_span_metrics:
            LOGGER.debug(
                "Detected %d LLM task span scoring metrics.",
                len(self._task_span_metrics),
            )

    def compute_regular_scores(
        self,
        scoring_inputs: Dict[str, Any],
        dataset_item_content: Dict[str, Any],
        task_output: Dict[str, Any],
    ) -> List[score_result.ScoreResult]:
        """
        Compute scores using regular metrics.

        Args:
            scoring_inputs: Prepared scoring inputs (kwargs for metrics)
            dataset_item_content: Dataset item content
            task_output: Task output

        Returns:
            List of score results from regular metrics
        """
        return _compute_metric_scores(
            scoring_metrics=self._regular_metrics,
            score_kwargs=scoring_inputs,
            scoring_key_mapping=self._scoring_key_mapping,
            dataset_item_content=dataset_item_content,
            task_output=task_output,
        )

    def compute_task_span_scores(
        self,
        scoring_inputs: Dict[str, Any],
        task_span: models.SpanModel,
        dataset_item_content: Dict[str, Any],
        task_output: Dict[str, Any],
    ) -> List[score_result.ScoreResult]:
        """
        Compute scores using task span metrics.

        Args:
            scoring_inputs: Prepared scoring inputs (kwargs for metrics)
            task_span: Span model containing task execution metadata
            dataset_item_content: Dataset item content
            task_output: Task output

        Returns:
            List of score results from task span metrics
        """
        score_kwargs = {
            **scoring_inputs,
            EVALUATION_SPAN_PARAMETER_NAME: task_span,
        }

        return _compute_metric_scores(
            scoring_metrics=self._task_span_metrics,
            score_kwargs=score_kwargs,
            scoring_key_mapping=self._scoring_key_mapping,
            dataset_item_content=dataset_item_content,
            task_output=task_output,
        )

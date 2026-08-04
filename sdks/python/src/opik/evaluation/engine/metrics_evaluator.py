import inspect
import logging
from typing import List, Dict, Any, Optional, Callable, Tuple

import opik.exceptions as exceptions
import opik.logging_messages as logging_messages
from opik.api_objects.dataset import dataset_item
from opik.decorator import error_info_collector
from opik.decorator.context_manager import span_context_manager
from opik.evaluation.metrics import (
    arguments_helpers,
    base_metric,
    score_result,
    arguments_validator,
)
from opik.evaluation.scorers import scorer_wrapper_metric
from opik.evaluation.suite_evaluators import llm_judge
from opik.evaluation.suite_evaluators.llm_judge import config as llm_judge_config
from opik.evaluation.types import ErrorTolerance, ScoringKeyMappingType
from opik.message_processing.emulation import models

from . import exception_analyzer


LOGGER = logging.getLogger(__name__)

EVALUATION_SPAN_PARAMETER_NAME = "task_span"
TRACE_TOOL_CONTEXT_PARAMETER_NAME = "trace_tool_context"
SCORE_ARGUMENTS_SPAN_SUFFIX = "score_arguments"


def _has_evaluation_span_parameter(func: Callable) -> bool:
    """Check if a scoring function expects the task_span parameter."""
    try:
        sig = inspect.signature(func)
        return EVALUATION_SPAN_PARAMETER_NAME in sig.parameters
    except (ValueError, TypeError):
        return False


def _accepts_trace_tool_context(func: Callable) -> bool:
    """Check if a scoring function accepts the trace_tool_context kwarg.

    Returns True when the signature names the parameter explicitly OR
    accepts ``**kwargs`` (which absorbs unknown kwargs). LLMJudge falls
    into the second case.
    """
    try:
        sig = inspect.signature(func)
    except (ValueError, TypeError):
        return False
    params = sig.parameters
    if TRACE_TOOL_CONTEXT_PARAMETER_NAME in params:
        return True
    return any(param.kind == inspect.Parameter.VAR_KEYWORD for param in params.values())


def _select_score_arguments(
    score_function: Callable, kwargs: Dict[str, Any]
) -> Tuple[List[Any], Dict[str, Any]]:
    """Split the scoring inputs into what the score signature can accept.

    Every dataset item key and task output key is offered to every metric, which
    breaks two kinds of signature:

    - A metric that does not declare ``**kwargs`` used to fail on every single
      item with an ``unexpected keyword argument`` TypeError — a message that
      reads like an SDK bug rather than a signature mismatch. Those keys are
      dropped now.
    - A positional-only parameter cannot be passed by keyword at all, so such a
      metric could never be scored. Those are returned separately, in signature
      order, to be passed positionally.

    Missing arguments are still reported: ``validate_score_arguments`` runs
    before this and only checks the parameters the metric declares, so filtering
    here cannot hide a key the metric actually asked for.
    """
    try:
        parameters = inspect.signature(score_function).parameters
    except (ValueError, TypeError):
        # Signature is not introspectable — pass everything, as before.
        return [], kwargs

    positional_arguments = [
        kwargs[name]
        for name, parameter in parameters.items()
        if parameter.kind == inspect.Parameter.POSITIONAL_ONLY and name in kwargs
    ]

    accepts_any_keyword = any(
        parameter.kind == inspect.Parameter.VAR_KEYWORD
        for parameter in parameters.values()
    )

    keyword_arguments: Dict[str, Any] = {}
    for name, value in kwargs.items():
        parameter = parameters.get(name)
        if parameter is None:
            if accepts_any_keyword:
                keyword_arguments[name] = value
        elif parameter.kind != inspect.Parameter.POSITIONAL_ONLY:
            keyword_arguments[name] = value

    return positional_arguments, keyword_arguments


def split_into_regular_and_task_span_metrics(
    scoring_metrics: List[base_metric.BaseMetric],
) -> Tuple[List[base_metric.BaseMetric], List[base_metric.BaseMetric]]:
    """
    Separate metrics into regular and task-span categories.

    Args:
        scoring_metrics: List of metrics to analyze.

    Returns:
        Tuple of (regular_metrics, task_span_metrics).
    """
    regular_metrics: List[base_metric.BaseMetric] = []
    task_span_metrics: List[base_metric.BaseMetric] = []

    for metric in scoring_metrics:
        if _has_evaluation_span_parameter(metric.score):
            task_span_metrics.append(metric)
        else:
            regular_metrics.append(metric)

    return regular_metrics, task_span_metrics


def _build_failed_score_result(
    metric_name: str, exception: Exception
) -> score_result.ScoreResult:
    """Represent an error raised outside the metric body as a failed score.

    ``reason`` is the exception message, matching what a failure raised inside
    ``score`` already produces. The structured payload goes to ``metadata``
    under the same ``error_info`` key used on spans and traces.
    """
    return score_result.ScoreResult(
        name=metric_name,
        value=0.0,
        reason=str(exception),
        metadata={"error_info": error_info_collector.collect(exception)},
        scoring_failed=True,
    )


def _extract_item_evaluators(
    item: dataset_item.DatasetItem,
    evaluator_model: Optional[str],
    error_tolerance: ErrorTolerance,
) -> Tuple[List[base_metric.BaseMetric], List[score_result.ScoreResult]]:
    """
    Extract evaluators from dataset item.

    If the item has evaluator configs, instantiate LLMJudge evaluators from them.

    Args:
        item: The dataset item.
        evaluator_model: Optional model name to use for LLMJudge evaluators.

    Returns:
        Tuple of (evaluator instances extracted from the item, failed score
        results for the evaluators that were configured but could not be run).
    """
    if not item.evaluators:
        return [], []

    evaluators: List[base_metric.BaseMetric] = []
    skipped_evaluator_scores: List[score_result.ScoreResult] = []
    for evaluator_item in item.evaluators:
        try:
            if evaluator_item.type == "llm_judge":
                config = llm_judge_config.LLMJudgeConfig(**evaluator_item.config)
                evaluator = llm_judge.LLMJudge.from_config(
                    config, init_kwargs={"model": evaluator_model}
                )
                evaluators.append(evaluator)
            else:
                # Not an error the caller can act on mid-run (an older SDK
                # against a newer dataset), so it must not abort the
                # evaluation. It must not disappear either: without a result
                # the item just looks under-evaluated (OPIK-6925).
                unsupported_type = exceptions.EvaluationError(
                    f"Unsupported evaluator type: {evaluator_item.type}. "
                    "Only 'llm_judge' is supported."
                )
                LOGGER.warning(str(unsupported_type))
                skipped_evaluator_scores.append(
                    _build_failed_score_result(evaluator_item.name, unsupported_type)
                )
        except Exception as exception:
            LOGGER.error(
                "Failed to instantiate evaluator from config: %s",
                evaluator_item.config,
                exc_info=True,
            )
            if error_tolerance < ErrorTolerance.ALL_SCORING_ERRORS:
                raise
            skipped_evaluator_scores.append(
                _build_failed_score_result(evaluator_item.name, exception)
            )

    return evaluators, skipped_evaluator_scores


def build_metrics_evaluator(
    item: Optional[dataset_item.DatasetItem],
    regular_metrics: List[base_metric.BaseMetric],
    scoring_key_mapping: ScoringKeyMappingType,
    evaluator_model: Optional[str],
    error_tolerance: ErrorTolerance = ErrorTolerance.METRIC_ERRORS,
) -> "MetricsEvaluator":
    """Build a MetricsEvaluator with suite-level + item-level metrics."""
    all_metrics: List[base_metric.BaseMetric] = list(regular_metrics)
    skipped_evaluator_scores: List[score_result.ScoreResult] = []
    if item is not None:
        item_evaluators, skipped_evaluator_scores = _extract_item_evaluators(
            item, evaluator_model=evaluator_model, error_tolerance=error_tolerance
        )
        all_metrics.extend(item_evaluators)

    judges = [m for m in all_metrics if isinstance(m, llm_judge.LLMJudge)]
    non_judges = [m for m in all_metrics if not isinstance(m, llm_judge.LLMJudge)]
    merged = llm_judge.LLMJudge.merged(judges)
    if merged is not None:
        all_metrics = [merged] + non_judges

    return MetricsEvaluator(
        scoring_metrics=all_metrics,
        scoring_key_mapping=scoring_key_mapping,
        skipped_evaluator_scores=skipped_evaluator_scores,
        error_tolerance=error_tolerance,
    )


def _compute_metric_scores(
    scoring_metrics: List[base_metric.BaseMetric],
    mapped_scoring_inputs: Dict[str, Any],
    scoring_key_mapping: ScoringKeyMappingType,
    dataset_item_content: Dict[str, Any],
    task_output: Dict[str, Any],
    trace_tool_context: Any,
    error_tolerance: ErrorTolerance,
) -> List[score_result.ScoreResult]:
    """
    Compute scores using given metrics.

    Args:
        scoring_metrics: List of metrics to compute
        mapped_scoring_inputs: Scoring inputs after key mapping (will be used for regular metrics)
        scoring_key_mapping: Mapping for renaming score arguments (empty dict if no mapping)
        dataset_item_content: Dataset item content (will be used for ScorerWrapperMetric)
        task_output: Task output (will be used for ScorerWrapperMetric)

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
                    task_span := mapped_scoring_inputs.get(
                        EVALUATION_SPAN_PARAMETER_NAME
                    )
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
                # Everything that prepares the call runs inside its own span, so a
                # failure here is reported the same way a failure inside `score`
                # is: `score` is `@track`-wrapped and gets its own span, while a
                # metric that never gets that far would otherwise leave no trace
                # of why — no feedback score is persisted for a failed score.
                with span_context_manager.start_as_current_span(
                    name=f"{metric.name}.{SCORE_ARGUMENTS_SPAN_SUFFIX}",
                ):
                    arguments_validator.validate_score_arguments(
                        metric=metric,
                        kwargs=mapped_scoring_inputs,
                        scoring_key_mapping=scoring_key_mapping,
                    )
                    # Only inject trace_tool_context into metrics whose
                    # signature can absorb it; otherwise the call would fail
                    # with "unexpected keyword argument" for narrow metrics.
                    if trace_tool_context is not None and _accepts_trace_tool_context(
                        metric.score
                    ):
                        score_kwargs = {
                            **mapped_scoring_inputs,
                            TRACE_TOOL_CONTEXT_PARAMETER_NAME: trace_tool_context,
                        }
                    else:
                        score_kwargs = mapped_scoring_inputs
                    positional_arguments, keyword_arguments = _select_score_arguments(
                        metric.score, score_kwargs
                    )

                result = metric.score(*positional_arguments, **keyword_arguments)

            LOGGER.debug("Metric %s score ended", metric.name)

            if isinstance(result, list):
                score_results += result
            else:
                score_results.append(result)

        except exceptions.ScoreMethodMissingArguments as exception:
            if error_tolerance < ErrorTolerance.ALL_SCORING_ERRORS:
                raise
            LOGGER.error(
                "Metric %s cannot be scored. Its score will be marked as failed. %s",
                metric.name,
                exception,
            )
            score_results.append(_build_failed_score_result(metric.name, exception))
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

            score_results.append(_build_failed_score_result(metric.name, exception))

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
        scoring_key_mapping: ScoringKeyMappingType,
        skipped_evaluator_scores: Optional[List[score_result.ScoreResult]] = None,
        error_tolerance: ErrorTolerance = ErrorTolerance.METRIC_ERRORS,
    ):
        self._scoring_key_mapping = scoring_key_mapping
        self._regular_metrics: List[base_metric.BaseMetric] = []
        self._task_span_metrics: List[base_metric.BaseMetric] = []
        self._skipped_evaluator_scores = skipped_evaluator_scores or []
        self._error_tolerance = error_tolerance

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

    @property
    def scoring_key_mapping(self) -> ScoringKeyMappingType:
        """Get the scoring key mapping."""
        return self._scoring_key_mapping

    def _analyze_metrics(
        self,
        scoring_metrics: List[base_metric.BaseMetric],
    ) -> None:
        """Separate metrics into regular and task-span categories."""
        self._regular_metrics, self._task_span_metrics = (
            split_into_regular_and_task_span_metrics(scoring_metrics)
        )

        if self.has_task_span_metrics:
            LOGGER.debug(
                "Detected %d LLM task span scoring metrics.",
                len(self._task_span_metrics),
            )

    def compute_regular_scores(
        self,
        dataset_item_content: Dict[str, Any],
        task_output: Dict[str, Any],
        trace_tool_context: Any = None,
    ) -> Tuple[List[score_result.ScoreResult], Dict[str, Any]]:
        """
        Compute scores using regular metrics.

        Args:
            dataset_item_content: Dataset item content
            task_output: Task output
            trace_tool_context: Optional agentic-judge context built from the
                local emulator. Threaded only to metrics whose score
                signature can accept it (LLMJudge in particular).

        Returns:
            Tuple of (score results, mapped scoring inputs used for scoring regular non-wrapper metrics)
        """
        mapped_scoring_inputs = arguments_helpers.create_scoring_inputs(
            dataset_item=dataset_item_content,
            task_output=task_output,
            scoring_key_mapping=self._scoring_key_mapping,
        )

        score_results = self._skipped_evaluator_scores + _compute_metric_scores(
            scoring_metrics=self._regular_metrics,
            mapped_scoring_inputs=mapped_scoring_inputs,
            scoring_key_mapping=self._scoring_key_mapping,
            dataset_item_content=dataset_item_content,
            task_output=task_output,
            trace_tool_context=trace_tool_context,
            error_tolerance=self._error_tolerance,
        )

        return score_results, mapped_scoring_inputs

    def compute_task_span_scores(
        self,
        dataset_item_content: Dict[str, Any],
        task_output: Dict[str, Any],
        task_span: models.SpanModel,
    ) -> Tuple[List[score_result.ScoreResult], Dict[str, Any]]:
        """
        Compute scores using task span metrics.

        Args:
            dataset_item_content: Dataset item content
            task_output: Task output
            task_span: Span model containing task execution metadata

        Returns:
            Tuple of (score results, mapped scoring inputs used for scoring regular non-wrapper metrics)
        """
        mapped_scoring_inputs = arguments_helpers.create_scoring_inputs(
            dataset_item=dataset_item_content,
            task_output=task_output,
            scoring_key_mapping=self._scoring_key_mapping,
        )

        mapped_scoring_inputs_with_span = {
            **mapped_scoring_inputs,
            EVALUATION_SPAN_PARAMETER_NAME: task_span,
        }

        score_results = _compute_metric_scores(
            scoring_metrics=self._task_span_metrics,
            mapped_scoring_inputs=mapped_scoring_inputs_with_span,
            scoring_key_mapping=self._scoring_key_mapping,
            dataset_item_content=dataset_item_content,
            task_output=task_output,
            trace_tool_context=None,
            error_tolerance=self._error_tolerance,
        )

        return score_results, mapped_scoring_inputs_with_span

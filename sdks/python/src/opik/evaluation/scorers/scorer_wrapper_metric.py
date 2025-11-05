from typing import Any, Callable, Dict, Optional, List, Union

from opik.evaluation.metrics import base_metric, score_result

from . import scorer_function
from ...message_processing.emulation import models


class ScorerWrapperMetric(base_metric.BaseMetric):
    """
    A wrapper metric that adapts a ScorerFunction to the BaseMetric interface.

    This class allows using ScorerFunction instances as BaseMetric instances,
    providing compatibility between the two interfaces.

    Args:
        scorer: The ScorerFunction to wrap
        name: Optional name for the metric. If not provided, uses the class name.
        track: Whether to track the metric. Defaults to True.
        project_name: Optional project name for tracking.

    Raises:
        ValueError if the scorer function is invalid.

    Example:
        >>> def my_scorer(dataset_item: Dict[str, Any], task_outputs: Dict[str, Any]) -> score_result.ScoreResult:
        >>>     return score_result.ScoreResult(name="my_metric", value=1.0)
        >>>
        >>> wrapper = ScorerWrapperMetric(scorer_function=my_scorer, name="wrapped_scorer")
        >>> result = wrapper.score(dataset_item={"text": "hello"}, task_outputs={"text": "hello"})
    """

    def __init__(
        self,
        scorer: scorer_function.ScorerFunction,
        name: str,
        track: bool = True,
        project_name: Optional[str] = None,
    ) -> None:
        super().__init__(name=name, track=track, project_name=project_name)
        self.scorer = scorer

        # validate scorer function
        scorer_function.validate_scorer_function(scorer)

    def score(
        self,
        dataset_item: Dict[str, Any],
        task_outputs: Dict[str, Any],
        **kwargs: Any,
    ) -> Union[score_result.ScoreResult, List[score_result.ScoreResult]]:
        """
        Score using the wrapped ScorerFunction.

        Args:
            dataset_item: The dataset item data to score against
            task_outputs: The output dictionary to be scored - can be the output of LLM task, etc.
            **kwargs: Additional keyword arguments (ignored by the scorer function)

        Returns:
            ScoreResult from the wrapped scorer function
        """
        return self.scorer(dataset_item=dataset_item, task_outputs=task_outputs)


class ScorerWrapperMetricTaskSpan(ScorerWrapperMetric):
    def __init__(
        self,
        scorer: scorer_function.ScorerFunction,
        name: str,
        track: bool = True,
        project_name: Optional[str] = None,
    ) -> None:
        super().__init__(
            scorer=scorer, name=name, track=track, project_name=project_name
        )

    def score(
        self,
        dataset_item: Dict[str, Any],
        task_outputs: Dict[str, Any],
        task_span: Optional[models.SpanModel] = None,
        **kwargs: Any,
    ) -> Union[score_result.ScoreResult, List[score_result.ScoreResult]]:
        """
        Score using the wrapped ScorerFunction.

        Args:
            dataset_item: The dataset item data to score against
            task_outputs: The output dictionary to be scored - can be the output of LLM task, etc.
            task_span: The collected task span data.
            **kwargs: Additional keyword arguments (ignored by the scorer function)

        Returns:
            ScoreResult from the wrapped scorer function
        """
        if task_span is not None and scorer_function.has_task_span_in_parameters(
            self.scorer
        ):
            return self.scorer(
                dataset_item=dataset_item,
                task_outputs=task_outputs,
                task_span=task_span,
            )

        return self.scorer(dataset_item=dataset_item, task_outputs=task_outputs)


def _scorer_name(scorer: Callable) -> str:
    return scorer.__name__


def wrap_scorer_functions(
    scorer_functions: List[scorer_function.ScorerFunction], project_name: Optional[str]
) -> List[base_metric.BaseMetric]:
    metrics: List[base_metric.BaseMetric] = []
    for f in scorer_functions:
        name = _scorer_name(f)
        if scorer_function.has_task_span_in_parameters(f):
            metrics.append(
                ScorerWrapperMetricTaskSpan(
                    scorer=f, project_name=project_name, name=name
                )
            )
        else:
            metrics.append(
                ScorerWrapperMetric(scorer=f, project_name=project_name, name=name)
            )

    return metrics

from typing import Any, Dict, Optional, Union

from opik.evaluation.metrics import base_metric, score_result
from .scorer_function import ScorerFunction, AsyncScorerFunction


class ScorerWrapperMetric(base_metric.BaseMetric):
    """
    A wrapper metric that adapts a ScorerFunction to the BaseMetric interface.

    This class allows using ScorerFunction instances as BaseMetric instances,
    providing compatibility between the two interfaces.

    Args:
        scorer_function: The ScorerFunction to wrap
        name: Optional name for the metric. If not provided, uses the class name.
        track: Whether to track the metric. Defaults to True.
        project_name: Optional project name for tracking.

    Example:
        >>> def my_scorer(scoring_inputs: Dict[str, Any], task_outputs: Dict[str, Any]) -> score_result.ScoreResult:
        >>>     return score_result.ScoreResult(name="my_metric", value=1.0)
        >>>
        >>> wrapper = ScorerWrapperMetric(scorer_function=my_scorer, name="wrapped_scorer")
        >>> result = wrapper.score(scoring_inputs={"text": "hello"}, task_outputs={"text": "hello"})
    """

    def __init__(
        self,
        scorer_function: Union[ScorerFunction, AsyncScorerFunction],
        name: Optional[str] = None,
        track: bool = True,
        project_name: Optional[str] = None,
    ) -> None:
        self.scorer_function = scorer_function
        name = name if name is not None else self.scorer_function.__name__
        super().__init__(name=name, track=track, project_name=project_name)

    def score(
        self,
        scoring_inputs: Dict[str, Any],
        task_outputs: Dict[str, Any],
        **kwargs: Any,
    ) -> score_result.ScoreResult:
        """
        Score using the wrapped ScorerFunction.

        Args:
            scoring_inputs: The expected/reference dictionary to score against - can be the dataset item content
            task_outputs: The output dictionary to be scored - can be the output of LLM task, etc.
            **kwargs: Additional keyword arguments (ignored by the scorer function)

        Returns:
            ScoreResult from the wrapped scorer function
        """
        return self.scorer_function(scoring_inputs, task_outputs)

    async def ascore(
        self,
        scoring_inputs: Dict[str, Any],
        task_outputs: Dict[str, Any],
        **kwargs: Any,
    ) -> score_result.ScoreResult:
        return await self.scorer_function(scoring_inputs, task_outputs)

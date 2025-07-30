from typing import Dict, Any, Optional, List, Union
import logging
import pydantic

from opik.evaluation.models import base_model, models_factory
from opik.evaluation.metrics import base_metric, score_result
from opik.evaluation.metrics.llm_judges.trajectory_accuracy import templates, parser
from opik import exceptions

LOGGER = logging.getLogger(__name__)


class TrajectoryAccuracyResponseFormat(pydantic.BaseModel):
    """Expected format for LLM response when evaluating trajectory accuracy."""

    score: float = pydantic.Field(
        ..., ge=0.0, le=1.0, description="Score between 0.0 and 1.0"
    )
    explanation: str = pydantic.Field(
        ..., min_length=1, description="Detailed explanation for the score"
    )


class TrajectoryAccuracy(base_metric.BaseMetric):
    """
    A metric that evaluates the accuracy of ReAct-style agent trajectories.

    This metric uses an LLM to judge whether an agent's sequence of thought/action/observation
    steps demonstrates effective reasoning and appropriate action selection to achieve the goal.
    It returns a score between 0.0 and 1.0 based on reasoning quality, action appropriateness,
    observation integration, goal achievement, and efficiency.

    Args:
        model: The LLM to use for evaluation. Can be a string (model name) or an
            `opik.evaluation.models.OpikBaseModel` subclass instance.
            `opik.evaluation.models.LiteLLMChatModel` is used by default.
        name: The name of the metric.
        track: Whether to track the metric. Defaults to True.
        project_name: Optional project name to track the metric in for the cases when
            there are no parent span/trace to inherit project name from.

    Example:
        >>> from opik.evaluation.metrics import TrajectoryAccuracy
        >>> trajectory_metric = TrajectoryAccuracy()
        >>> result = trajectory_metric.score(
        ...     goal="Find the weather in Paris",
        ...     trajectory=[{
        ...         'thought': 'I need to search for weather information',
        ...         'action': 'search_weather(location="Paris")',
        ...         'observation': 'Weather: 22°C, sunny'
        ...     }],
        ...     final_result="The weather in Paris is 22°C and sunny"
        ... )
        >>> print(result.value)  # A float between 0.0 and 1.0
        >>> print(result.reason)  # Explanation for the score
    """

    def __init__(
        self,
        model: Optional[Union[str, base_model.OpikBaseModel]] = None,
        name: str = "trajectory_accuracy_metric",
        track: bool = True,
        project_name: Optional[str] = None,
    ):
        super().__init__(name=name, track=track, project_name=project_name)
        self._init_model(model)

    def _init_model(
        self, model: Optional[Union[str, base_model.OpikBaseModel]]
    ) -> None:
        if isinstance(model, base_model.OpikBaseModel):
            self._model = model
        else:
            self._model = models_factory.get(model_name=model)

    def score(
        self,
        goal: str,
        trajectory: List[Dict[str, Any]],
        final_result: str,
        **ignored_kwargs: Any,
    ) -> score_result.ScoreResult:
        """
        Calculate the trajectory accuracy score for the given ReAct-style agent trajectory.

        Args:
            goal: The intended goal or task description.
            trajectory: List of steps with 'thought', 'action', 'observation' keys.
            final_result: The final outcome achieved.
            **ignored_kwargs: Additional keyword arguments that are ignored.

        Returns:
            score_result.ScoreResult: A ScoreResult object with a value between 0.0 and 1.0
                indicating trajectory accuracy, along with an explanation for the verdict.
        """
        try:
            example = {
                "goal": goal,
                "trajectory": trajectory,
                "final_result": final_result,
            }

            prompt = templates.create_evaluation_prompt(example)

            response = self._model.generate_string(
                input=prompt, response_format=TrajectoryAccuracyResponseFormat
            )

            return parser.parse_evaluation_response(response, self.name)

        except Exception as e:
            LOGGER.error(f"Trajectory accuracy evaluation failed: {e}", exc_info=True)
            raise exceptions.MetricComputationError(
                f"Trajectory accuracy evaluation failed: {str(e)}"
            ) from e

    async def ascore(
        self,
        goal: str,
        trajectory: List[Dict[str, Any]],
        final_result: str,
        **ignored_kwargs: Any,
    ) -> score_result.ScoreResult:
        """
        Asynchronously calculate the trajectory accuracy score.

        Args:
            goal: The intended goal or task description.
            trajectory: List of steps with 'thought', 'action', 'observation' keys.
            final_result: The final outcome achieved.
            **ignored_kwargs: Additional keyword arguments that are ignored.

        Returns:
            score_result.ScoreResult: A ScoreResult object with a value between 0.0 and 1.0
                indicating trajectory accuracy, along with an explanation for the verdict.
        """
        try:
            example = {
                "goal": goal,
                "trajectory": trajectory,
                "final_result": final_result,
            }

            prompt = templates.create_evaluation_prompt(example)

            response = await self._model.agenerate_string(
                input=prompt, response_format=TrajectoryAccuracyResponseFormat
            )

            return parser.parse_evaluation_response(response, self.name)

        except Exception as e:
            LOGGER.error(f"Trajectory accuracy evaluation failed: {e}", exc_info=True)
            raise exceptions.MetricComputationError(
                f"Trajectory accuracy evaluation failed: {str(e)}"
            ) from e

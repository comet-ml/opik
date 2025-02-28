import json
import logging
from typing import Union, Optional, Any
import pydantic
from opik.evaluation.models import base_model, models_factory
from opik.evaluation.metrics import score_result, base_metric

from . import template
from opik.exceptions import MetricComputationError

LOGGER = logging.getLogger(__name__)


class UsefulnessResponseFormat(pydantic.BaseModel):
    score: float
    reason: str


class Usefulness(base_metric.BaseMetric):
    """
    A metric that evaluates how useful an output is given an input.

    This metric uses a language model to assess the usefulness of the given output
    based on the input. It returns a score between 0.0 and 1.0,
    where higher values indicate higher usefulness.

    Args:
        model: The language model to use for usefulness assessment. Can be a string (model name) or an `opik.evaluation.models.OpikBaseModel` subclass instance.
            `opik.evaluation.models.LiteLLMChatModel` is used by default.
        name: The name of the metric. Defaults to "UsefulnessMetric".
        track: Whether to track the metric. Defaults to True.

    Example:
        >>> from opik.evaluation.metrics import Usefulness
        >>> usefulness_metric = Usefulness()
        >>> result = usefulness_metric.score("What's the capital of France?", "The capital of France is Paris.")
        >>> print(result.value)  # A float between 0.0 and 1.0
        >>> print(result.reason)  # Explanation for the score
    """

    def __init__(
        self,
        model: Optional[Union[str, base_model.OpikBaseModel]] = None,
        name: str = "UsefulnessMetric",
        track: bool = True,
    ):
        super().__init__(
            name=name,
            track=track,
        )

        self._init_model(model)

    def _init_model(
        self, model: Optional[Union[str, base_model.OpikBaseModel]]
    ) -> None:
        if isinstance(model, base_model.OpikBaseModel):
            self._model = model
        else:
            self._model = models_factory.get(model_name=model)

    def score(
        self, input: str, output: str, **ignored_kwargs: Any
    ) -> score_result.ScoreResult:
        """
        Calculate the usefulness score for the given input-output pair.

        Args:
            input: The input text to be evaluated.
            output: The output text to be evaluated.
            **ignored_kwargs: Additional keyword arguments that are ignored.

        Returns:
            score_result.ScoreResult: A ScoreResult object containing the usefulness score
            (between 0.0 and 1.0) and a reason for the score.
        """
        llm_query = template.generate_query(
            input=input,
            output=output,
        )
        model_output = self._model.generate_string(
            input=llm_query, response_format=UsefulnessResponseFormat
        )

        return self._parse_model_output(model_output)

    async def ascore(
        self, input: str, output: str, **ignored_kwargs: Any
    ) -> score_result.ScoreResult:
        """
        Asynchronously calculate the usefulness score for the given input-output pair.

        Args:
            input: The input text to be evaluated.
            output: The output text to be evaluated.
            **ignored_kwargs: Additional keyword arguments that are ignored.

        Returns:
            score_result.ScoreResult: A ScoreResult object containing the usefulness score
            (between 0.0 and 1.0) and a reason for the score.
        """
        llm_query = template.generate_query(
            input=input,
            output=output,
        )
        model_output = await self._model.agenerate_string(
            input=llm_query, response_format=UsefulnessResponseFormat
        )

        return self._parse_model_output(model_output)

    def _parse_model_output(self, content: str) -> score_result.ScoreResult:
        """Parse the model output string into a ScoreResult."""
        try:
            dict_content = json.loads(content)
            score: float = float(dict_content["score"])

            if not (0.0 <= score <= 1.0):
                raise MetricComputationError(
                    f"Usefulness score must be between 0.0 and 1.0, got {score}"
                )

            return score_result.ScoreResult(
                name=self.name, value=score, reason=dict_content["reason"]
            )
        except Exception as e:
            LOGGER.error(f"Failed to parse model output: {e}", exc_info=True)
            raise MetricComputationError(
                f"Failed to parse usefulness score from model output: {repr(e)}"
            )

from typing import Any, List, Optional, Union
from opik.evaluation.metrics.llm_judges.moderation import parser
import pydantic
from opik.evaluation.metrics import base_metric, score_result
from opik.evaluation.models import base_model, models_factory
from . import template


class ModerationResponseFormat(pydantic.BaseModel):
    score: float
    reason: str


class Moderation(base_metric.BaseMetric):
    """
    A metric that evaluates the moderation level of an input-output pair using an LLM.

    This metric uses a language model to assess the moderation level of the given input and output.
    It returns a score between 0.0 and 1.0, where higher values indicate more appropriate content.

    Args:
        model: The language model to use for moderation. Can be a string (model name) or an `opik.evaluation.models.OpikBaseModel` subclass instance.
            `opik.evaluation.models.LiteLLMChatModel` is used by default.
        name: The name of the metric. Defaults to "moderation_metric".
        few_shot_examples: A list of few-shot examples to be used in the query. If None, default examples will be used.
        track: Whether to track the metric. Defaults to True.
        project_name: Optional project name to track the metric in for the cases when
            there are no parent span/trace to inherit project name from.

    Example:
        >>> from opik.evaluation.metrics import Moderation
        >>> moderation_metric = Moderation()
        >>> result = moderation_metric.score("Hello, how can I help you?")
        >>> print(result.value)  # A float between 0.0 and 1.0
        >>> print(result.reason)  # Explanation for the score
    """

    def __init__(
        self,
        model: Optional[Union[str, base_model.OpikBaseModel]] = None,
        name: str = "moderation_metric",
        few_shot_examples: Optional[List[template.FewShotExampleModeration]] = None,
        track: bool = True,
        project_name: Optional[str] = None,
    ):
        super().__init__(
            name=name,
            track=track,
            project_name=project_name,
        )

        self._init_model(model)
        self.few_shot_examples = [] if few_shot_examples is None else few_shot_examples

    def _init_model(
        self, model: Optional[Union[str, base_model.OpikBaseModel]]
    ) -> None:
        if isinstance(model, base_model.OpikBaseModel):
            self._model = model
        else:
            self._model = models_factory.get(model_name=model)

    def score(self, output: str, **ignored_kwargs: Any) -> score_result.ScoreResult:
        """
        Calculate the moderation score for the given input-output pair.

        Args:
            output: The output text to be evaluated.
            **ignored_kwargs (Any): Additional keyword arguments that are ignored.

        Returns:
            score_result.ScoreResult: A ScoreResult object containing the moderation score
            (between 0.0 and 1.0) and a reason for the score.
        """
        llm_query = template.generate_query(
            output=output, few_shot_examples=self.few_shot_examples
        )
        model_output = self._model.generate_string(
            input=llm_query, response_format=ModerationResponseFormat
        )

        return parser.parse_model_output(content=model_output, name=self.name)

    async def ascore(
        self, output: str, **ignored_kwargs: Any
    ) -> score_result.ScoreResult:
        """
        Asynchronously calculate the moderation score for the given input-output pair.

        This method is the asynchronous version of :meth:`score`. For detailed documentation,
        please refer to the :meth:`score` method.

        Args:
            output: The output text to be evaluated.
            **ignored_kwargs: Additional keyword arguments that are ignored.

        Returns:
            score_result.ScoreResult: A ScoreResult object with the moderation score and reason.
        """

        llm_query = template.generate_query(
            output=output, few_shot_examples=self.few_shot_examples
        )
        model_output = await self._model.agenerate_string(
            input=llm_query, response_format=ModerationResponseFormat
        )

        return parser.parse_model_output(content=model_output, name=self.name)

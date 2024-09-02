import json
import logging
from typing import Any, List, Optional, Union

from opik import logging_messages
from opik.evaluation.metrics import base_metric, score_result
from opik.evaluation.models import base_model, models_factory
from . import template
from ... import exceptions

LOGGER = logging.getLogger(__name__)


class Moderation(base_metric.BaseMetric):
    """
    A metric that evaluates the moderation level of an input-output pair using an LLM.

    This metric uses a language model to assess the moderation level of the given input and output.
    It returns a score between 0.0 and 1.0, where higher values indicate more appropriate content.

    Args:
        model: The language model to use for moderation. Can be a string (model name) or a CometBaseModel instance.
        name: The name of the metric. Defaults to "moderation_metric".
        few_shot_examples: A list of few-shot examples to be used in the query. If None, default examples will be used.

    Example:
        >>> from comet_llm_eval.evaluation.metrics import Moderation
        >>> moderation_metric = Moderation()
        >>> result = moderation_metric.score("Hello", "Hello, how can I help you?")
        >>> print(result.value)  # A float between 0.0 and 1.0
        >>> print(result.reason)  # Explanation for the score
    """

    def __init__(
        self,
        model: Optional[Union[str, base_model.OpikBaseModel]] = None,
        name: str = "moderation_metric",
        few_shot_examples: Optional[List[template.FewShotExampleModeration]] = None,
    ):
        super().__init__(
            name=name,
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

    def score(self, input: str, **ignored_kwargs: Any) -> score_result.ScoreResult:
        """
        Calculate the moderation score for the given input-output pair.

        Args:
            input: The input text to be evaluated.
            output: The output text to be evaluated.
            **ignored_kwargs (Any): Additional keyword arguments that are ignored.

        Returns:
            score_result.ScoreResult: A ScoreResult object containing the moderation score
            (between 0.0 and 1.0) and a reason for the score.
        """
        llm_query = template.generate_query(
            input=input, few_shot_examples=self.few_shot_examples
        )
        model_output = self._model.generate(input=llm_query)

        return self._parse_model_output(model_output)

    async def ascore(
        self, input: str, **ignored_kwargs: Any
    ) -> score_result.ScoreResult:
        """
        Asynchronously calculate the moderation score for the given input-output pair.

        This method is the asynchronous version of :meth:`score`. For detailed documentation,
        please refer to the :meth:`score` method.

        Args:
            input: The input text to be evaluated.
            output: The output text to be evaluated.
            **ignored_kwargs: Additional keyword arguments that are ignored.

        Returns:
            score_result.ScoreResult: A ScoreResult object with the moderation score and reason.
        """

        llm_query = template.generate_query(
            input=input, few_shot_examples=self.few_shot_examples
        )
        model_output = await self._model.agenerate(input=llm_query)

        return self._parse_model_output(model_output)

    def _parse_model_output(self, content: str) -> score_result.ScoreResult:
        try:
            dict_content = json.loads(content)
            score: float = dict_content[template.VERDICT_KEY]

            if not (0.0 <= score <= 1.0):
                score = 0.5

            return score_result.ScoreResult(
                name=self.name, value=score, reason=dict_content[template.REASON_KEY]
            )
        except Exception:
            raise exceptions.MetricComputationError(
                logging_messages.MODERATION_SCORE_CALC_FAILED
            )

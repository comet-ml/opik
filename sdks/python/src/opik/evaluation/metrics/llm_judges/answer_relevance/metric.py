import json
import logging
from typing import Any, List, Optional, Union

from opik import logging_messages
from opik.evaluation.metrics import base_metric, score_result
from opik.evaluation.models import base_model, models_factory

from . import template
from ... import exceptions

LOGGER = logging.getLogger(__name__)


class AnswerRelevance(base_metric.BaseMetric):
    """
    A metric that evaluates the relevance of an answer to a given input using an LLM.

    This metric uses a language model to assess how well the given output (answer)
    addresses the provided input (question) within the given context. It returns a score
    between 0.0 and 1.0, where higher values indicate better answer relevance.

    Args:
        model: The language model to use for evaluation. Can be a string (model name) or a CometBaseModel instance.
        name: The name of the metric. Defaults to "AnswerRelevanceMetric".

    Example:
        >>> from comet_llm_eval.evaluation.metrics import AnswerRelevance
        >>> answer_relevance_metric = AnswerRelevance()
        >>> result = answer_relevance_metric.score("What's the capital of France?", "The capital of France is Paris.", ["France is a country in Europe."])
        >>> print(result.value)
        0.9
        >>> print(result.reason)
        The answer directly addresses the user's query by correctly identifying Paris as the capital of France. ...
    """

    def __init__(
        self,
        model: Optional[Union[str, base_model.OpikBaseModel]] = None,
        name: str = "answer_relevance_metric",
        few_shot_examples: Optional[
            List[template.FewShotExampleAnswerRelevance]
        ] = None,
    ):
        super().__init__(
            name=name,
        )

        self._init_model(model)
        if few_shot_examples is None:
            self._few_shot_examples = template.FEW_SHOT_EXAMPLES
        else:
            self._few_shot_examples = few_shot_examples

    def _init_model(
        self, model: Optional[Union[str, base_model.OpikBaseModel]]
    ) -> None:
        if isinstance(model, base_model.OpikBaseModel):
            self._model = model
        else:
            self._model = models_factory.get(model_name=model)

    def score(
        self, input: str, output: str, context: List[str], **ignored_kwargs: Any
    ) -> score_result.ScoreResult:
        """
        Calculate the answer relevance score for the given input-output pair.

        Args:
            input: The input text (question) to be evaluated.
            output: The output text (answer) to be evaluated.
            context: A list of context strings relevant to the input.
            **ignored_kwargs: Additional keyword arguments that are ignored.

        Returns:
            score_result.ScoreResult: A ScoreResult object containing the answer relevance score
            (between 0.0 and 1.0) and a reason for the score.
        """
        llm_query = template.generate_query(input=input, output=output, context=context)
        model_output = self._model.generate(input=llm_query)

        return self._parse_model_output(model_output)

    async def ascore(
        self, input: str, output: str, context: List[str], **ignored_kwargs: Any
    ) -> score_result.ScoreResult:
        """
        Asynchronously calculate the answer relevance score for the given input-output pair.

        This method is the asynchronous version of :meth:`score`. For detailed documentation,
        please refer to the :meth:`score` method.

        Args:
            input: The input text (question) to be evaluated.
            output: The output text (answer) to be evaluated.
            context: A list of context strings relevant to the input.
            **ignored_kwargs: Additional keyword arguments that are ignored.

        Returns:
            score_result.ScoreResult: A ScoreResult object with the answer relevance score and reason.
        """
        llm_query = template.generate_query(input=input, output=output, context=context)
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
                logging_messages.ANSWER_RELEVANCE_SCORE_CALC_FAILED
            )

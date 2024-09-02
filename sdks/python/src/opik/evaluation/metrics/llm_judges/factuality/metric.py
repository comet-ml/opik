import json
import logging
import pprint
from typing import Union, Optional, List, Any

from opik.evaluation.models import base_model, models_factory
from opik.evaluation.metrics import score_result, base_metric
from opik import logging_messages

from . import template
from ...exceptions import MetricComputationError

LOGGER = logging.getLogger(__name__)


class Factuality(base_metric.BaseMetric):
    """
    A metric that evaluates the factual accuracy of an output given an input and context.

    This metric uses a language model to assess the factual correctness of the given output
    based on the input and provided context. It returns a score between 0.0 and 1.0,
    where higher values indicate higher factual accuracy.

    Args:
        model: The language model to use for factuality assessment. Can be a string (model name) or a CometBaseModel instance.
        name: The name of the metric. Defaults to "FactualityMetric".
        few_shot_examples: A list of few-shot examples to be used in the query. If None, default examples will be used.

    Example:
        >>> from comet_llm_eval.evaluation.metrics import Factuality
        >>> factuality_metric = Factuality()
        >>> result = factuality_metric.score("What's the capital of France?", "The capital of France is Paris.", ["France is a country in Europe."])
        >>> print(result.value)  # A float between 0.0 and 1.0
        >>> print(result.reason)  # Explanation for the score
    """

    def __init__(
        self,
        model: Optional[Union[str, base_model.OpikBaseModel]] = None,
        name: str = "FactualityMetric",
        few_shot_examples: Optional[List[template.FewShotExampleFactuality]] = None,
    ):
        super().__init__(
            name=name,
        )

        self._init_model(model)
        self.few_shot_examples = few_shot_examples or template.FEW_SHOT_EXAMPLES

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
        Calculate the factuality score for the given input-output pair and context.

        Args:
            input: The input text to be evaluated.
            output: The output text to be evaluated.
            context: A list of context strings to be used for factuality assessment.
            **ignored_kwargs: Additional keyword arguments that are ignored.

        Returns:
            score_result.ScoreResult: A ScoreResult object containing the factuality score
            (between 0.0 and 1.0) and a reason for the score.
        """
        llm_query = template.generate_query(
            input=input,
            output=output,
            context=context,
            few_shot_examples=self.few_shot_examples,
        )
        model_output = self._model.generate(input=llm_query)

        return self._parse_model_output(model_output)

    async def ascore(
        self, input: str, output: str, context: List[str], **ignored_kwargs: Any
    ) -> score_result.ScoreResult:
        """
        Asynchronously calculate the factuality score for the given input-output pair and context.

        This method is the asynchronous version of :meth:`score`. For detailed documentation,
        please refer to the :meth:`score` method.

        Args:
            input: The input text to be evaluated.
            output: The output text to be evaluated.
            context: A list of context strings to be used for factuality assessment.
            **ignored_kwargs: Additional keyword arguments that are ignored.

        Returns:
            score_result.ScoreResult: A ScoreResult object with the factuality score and reason.
        """
        llm_query = template.generate_query(
            input=input,
            output=output,
            context=context,
            few_shot_examples=self.few_shot_examples,
        )
        model_output = await self._model.agenerate(input=llm_query)

        return self._parse_model_output(model_output)

    def _parse_model_output(self, content: str) -> score_result.ScoreResult:
        try:
            list_content = json.loads(content)

            reason = ""
            score = 0.0

            for claim in list_content:
                pprint.pprint(claim)
                verdict = claim["verdict"]
                reason += claim["reason"] + "\n"

                if verdict == template.VERDICT_TRUTH:
                    score += 1.0
                elif verdict == template.VERDICT_LIE:
                    score += 0.0
                elif verdict == template.VERDICT_UNCLEAR:
                    score += 0.5

            score /= len(list_content)

            return score_result.ScoreResult(name=self.name, value=score, reason=reason)
        except Exception:
            raise MetricComputationError(logging_messages.FACTUALITY_SCORE_CALC_FAILED)

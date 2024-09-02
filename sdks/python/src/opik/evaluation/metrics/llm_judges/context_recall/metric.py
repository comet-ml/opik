import json
import logging
from typing import Any, List, Optional, Union

from opik import logging_messages
from opik.evaluation.metrics import base_metric, score_result
from opik.evaluation.models import base_model, models_factory

from . import template
from ... import exceptions

LOGGER = logging.getLogger(__name__)


class ContextRecall(base_metric.BaseMetric):
    """
    A metric that evaluates the context recall of an input-output pair using an LLM.

    This metric uses a language model to assess how well the given output incorporates
    the provided context for the given input. It returns a score between 0.0 and 1.0,
    where higher values indicate better context recall.

    Args:
        model: The language model to use for evaluation. Can be a string (model name) or a CometBaseModel instance.
        name: The name of the metric. Defaults to "ContextRecallMetric".
        few_shot_examples: A list of few-shot examples to provide to the model. If None, uses the default few-shot examples.

    Example:
        >>> from comet_llm_eval.evaluation.metrics import ContextRecall
        >>> context_recall_metric = ContextRecall()
        >>> result = context_recall_metric.score("What's the capital of France?", "The capital of France is Paris.", "Paris", ["France is a country in Europe."])
        >>> print(result.value)
        0.9
        >>> print(result.reason)
        The LLM's response is highly accurate, correctly identifying 'Paris' as the capital of France and aligning with the expected answer ...
    """

    def __init__(
        self,
        model: Optional[Union[str, base_model.OpikBaseModel]] = None,
        name: str = "context_recall_metric",
        few_shot_examples: Optional[List[template.FewShotExampleContextRecall]] = None,
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
        self,
        input: str,
        output: str,
        expected_output: str,
        context: List[str],
        **ignored_kwargs: Any,
    ) -> score_result.ScoreResult:
        """
        Calculate the context recall score for the given input-output pair.

        Args:
            input: The input text to be evaluated.
            output: The output text to be evaluated.
            expected_output: The expected output for the given input.
            context: A list of context strings relevant to the input.
            **ignored_kwargs: Additional keyword arguments that are ignored.

        Returns:
            score_result.ScoreResult: A ScoreResult object containing the context recall score
            (between 0.0 and 1.0) and a reason for the score.
        """
        llm_query = template.generate_query(
            input=input,
            output=output,
            expected_output=expected_output,
            context=context,
            few_shot_examples=self.few_shot_examples,
        )
        model_output = self._model.generate(input=llm_query)

        return self._parse_model_output(model_output)

    async def ascore(
        self,
        input: str,
        output: str,
        expected_output: str,
        context: List[str],
        **ignored_kwargs: Any,
    ) -> score_result.ScoreResult:
        """
        Asynchronously calculate the context recall score for the given input-output pair.

        This method is the asynchronous version of :meth:`score`. For detailed documentation,
        please refer to the :meth:`score` method.

        Args:
            input: The input text to be evaluated.
            output: The output text to be evaluated.
            expected_output: The expected output for the given input.
            context: A list of context strings relevant to the input.
            **ignored_kwargs: Additional keyword arguments that are ignored.

        Returns:
            score_result.ScoreResult: A ScoreResult object with the context recall score and reason.
        """
        llm_query = template.generate_query(
            input=input,
            output=output,
            expected_output=expected_output,
            context=context,
            few_shot_examples=self.few_shot_examples,
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
                logging_messages.CONTEXT_RECALL_SCORE_CALC_FAILED
            )

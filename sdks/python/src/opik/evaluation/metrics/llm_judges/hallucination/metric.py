import json
import logging
from typing import Union, Optional, List, Any
import pydantic

from opik.evaluation.models import base_model, models_factory
from opik.evaluation.metrics import score_result, base_metric
from opik import logging_messages

from . import template
from ... import exceptions

LOGGER = logging.getLogger(__name__)


class HallucinationResponseFormat(pydantic.BaseModel):
    score: int
    reason: List[str]


class Hallucination(base_metric.BaseMetric):
    """
    A metric that evaluates whether an LLM's output contains hallucinations based on given input and context.

    This metric uses another LLM to judge if the output is factual or contains hallucinations.
    It returns a score of 1.0 if hallucination is detected, and 0.0 otherwise.

    Args:
        model: The LLM to use for evaluation. Can be a string (model name) or a CometBaseModel instance.
        name: The name of the metric.
        few_shot_examples: A list of few-shot examples to use for hallucination detection.  If None, default examples will be used.

    Example:
        >>> from opik.evaluation.metrics import Hallucination
        >>> hallucination_metric = Hallucination()
        >>> result = hallucination_metric.score(
        ...     input="What is the capital of France?",
        ...     output="The capital of France is London.",
        ...     context=["The capital of France is Paris."]
        ... )
        >>> print(result.value)
        1.0
        >>> print(result.reason)
        The answer provided states that the capital of France is London, which contradicts the fact stated in the context that the capital of France is Paris.
    """

    def __init__(
        self,
        model: Optional[Union[str, base_model.OpikBaseModel]] = None,
        name: str = "hallucination_metric",
        few_shot_examples: Optional[List[template.FewShotExampleHallucination]] = None,
    ):
        super().__init__(name=name)
        self._init_model(model)
        self.few_shot_examples = few_shot_examples

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
        context: Optional[List[str]] = None,
        **ignored_kwargs: Any,
    ) -> score_result.ScoreResult:
        """
        Calculate the hallucination score for the given input, output, and optional context field.

        Args:
            input: The original input/question.
            output: The LLM's output to evaluate.
            context: A list of context strings. If not provided, the presence of hallucinations will be evaluated based on the output only.
            **ignored_kwargs: Additional keyword arguments that are ignored.

        Returns:
            score_result.ScoreResult: A ScoreResult object with a value of 1.0 if hallucination
                is detected, 0.0 otherwise, along with the reason for the verdict.
        """
        llm_query = template.generate_query(
            input=input,
            output=output,
            context=context,
            few_shot_examples=self.few_shot_examples,
        )
        model_output = self._model.generate_string(
            input=llm_query, response_format=HallucinationResponseFormat
        )

        return self._parse_model_output(model_output)

    async def ascore(
        self,
        input: str,
        output: str,
        context: Optional[List[str]] = None,
        **ignored_kwargs: Any,
    ) -> score_result.ScoreResult:
        """
        Asynchronously calculate the hallucination score for the given input, output, and optional context field.

        Args:
            input: The original input/question.
            output: The LLM's output to evaluate.
            context: A list of context strings. If not provided, the presence of hallucinations will be evaluated based on the output only.
            **ignored_kwargs: Additional keyword arguments that are ignored.

        Returns:
            score_result.ScoreResult: A ScoreResult object with a value of 1.0 if hallucination
                is detected, 0.0 otherwise, along with the reason for the verdict.
        """
        llm_query = template.generate_query(
            input=input,
            output=output,
            context=context,
            few_shot_examples=self.few_shot_examples,
        )
        model_output = await self._model.agenerate_string(
            input=llm_query, response_format=HallucinationResponseFormat
        )

        return self._parse_model_output(model_output)

    def _parse_model_output(self, content: str) -> score_result.ScoreResult:
        try:
            dict_content = json.loads(content)
            score = dict_content["score"]
            return score_result.ScoreResult(
                name=self.name,
                value=score,
                reason=str(dict_content["reason"]),
            )
        except Exception:
            raise exceptions.MetricComputationError(
                logging_messages.HALLUCINATION_DETECTION_FAILED
            )

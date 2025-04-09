import json
import logging
from typing import Any, List, Optional, Union
import pydantic

from opik import logging_messages
from opik.evaluation.metrics import base_metric, score_result
from opik.evaluation.models import base_model, models_factory

from . import templates
from opik import exceptions

LOGGER = logging.getLogger(__name__)


class AnswerRelevanceResponseFormat(pydantic.BaseModel):
    answer_relevance_score: float
    reason: str


class AnswerRelevance(base_metric.BaseMetric):
    """
    A metric that evaluates the relevance of an answer to a given input using an LLM.

    This metric uses a language model to assess how well the given output (answer)
    addresses the provided input (question) within the given context. It returns a score
    between 0.0 and 1.0, where higher values indicate better answer relevance.

    Args:
        model: The language model to use for evaluation. Can be a string (model name) or an `opik.evaluation.models.OpikBaseModel` subclass instance.
            `opik.evaluation.models.LiteLLMChatModel` is used by default.
        name: The name of the metric. Defaults to "AnswerRelevanceMetric".
        few_shot_examples: A list of dict to include as examples to the prompt query. Context key is required.
            If not provided, Opik's generic examples will be used.
        few_shot_examples_no_context: A list of dict to include as examples to the prompt query in no-context mode (so, 'context' key is not needed).
            If not provided, Opik's generic examples will be used.
        require_context: if set to False, execution in no-context mode is allowed. Default is True.
        track: Whether to track the metric. Defaults to True.

    Example:
        >>> from opik.evaluation.metrics import AnswerRelevance
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
            List[templates.FewShotExampleWithContextAnswerRelevance]
        ] = None,
        few_shot_examples_no_context: Optional[
            List[templates.FewShotExampleNoContextAnswerRelevance]
        ] = None,
        require_context: bool = True,
        track: bool = True,
    ):
        super().__init__(
            name=name,
            track=track,
        )
        self._require_context = require_context
        self._init_model(model)
        self._init_few_shot_examples(
            few_shot_examples_with_context=few_shot_examples,
            few_shot_examples_no_context=few_shot_examples_no_context,
        )

    def _init_model(
        self, model: Optional[Union[str, base_model.OpikBaseModel]]
    ) -> None:
        if isinstance(model, base_model.OpikBaseModel):
            self._model = model
        else:
            self._model = models_factory.get(model_name=model)

    def _init_few_shot_examples(
        self,
        few_shot_examples_with_context: Optional[
            List[templates.FewShotExampleWithContextAnswerRelevance]
        ],
        few_shot_examples_no_context: Optional[
            List[templates.FewShotExampleNoContextAnswerRelevance]
        ],
    ) -> None:
        self._few_shot_examples_no_context = (
            few_shot_examples_no_context
            if few_shot_examples_no_context
            else templates.FEW_SHOT_EXAMPLES_NO_CONTEXT
        )

        self._few_shot_examples_with_context = (
            few_shot_examples_with_context
            if few_shot_examples_with_context
            else templates.FEW_SHOT_EXAMPLES_WITH_CONTEXT
        )

    def score(
        self,
        input: str,
        output: str,
        context: Optional[List[str]] = None,
        **ignored_kwargs: Any,
    ) -> score_result.ScoreResult:
        """
        Calculate the answer relevance score for the given input-output pair.

        Args:
            input: The input text (question) to be evaluated.
            output: The output text (answer) to be evaluated.
            context: A list of context strings relevant to the input. If no context is given, the
                metric is calculated in no-context mode (the prompt template will not refer to context at all)
            **ignored_kwargs: Additional keyword arguments that are ignored.

        Returns:
            score_result.ScoreResult: A ScoreResult object containing the answer relevance score
            (between 0.0 and 1.0) and a reason for the score.
        """
        llm_query = self._generate_llm_query(
            input=input, output=output, context=context
        )

        model_output = self._model.generate_string(
            input=llm_query, response_format=AnswerRelevanceResponseFormat
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
        llm_query = self._generate_llm_query(
            input=input, output=output, context=context
        )
        model_output = await self._model.agenerate_string(
            input=llm_query, response_format=AnswerRelevanceResponseFormat
        )

        return self._parse_model_output(model_output)

    def _parse_model_output(self, content: str) -> score_result.ScoreResult:
        try:
            dict_content = json.loads(content)
            score: float = dict_content["answer_relevance_score"]

            if not (0.0 <= score <= 1.0):
                score = 0.5

            return score_result.ScoreResult(
                name=self.name, value=score, reason=dict_content["reason"]
            )
        except Exception:
            raise exceptions.MetricComputationError(
                logging_messages.ANSWER_RELEVANCE_SCORE_CALC_FAILED
            )

    def _generate_llm_query(
        self, input: str, output: str, context: Optional[List[str]]
    ) -> str:
        if not context:
            if self._require_context:
                raise exceptions.MetricComputationError(
                    f"{self.name} requires context by default. If you want to allow execution in no-context mode, "
                    f"enable it via `AnswerRelevancy(require_context=False)"
                )

            llm_query = templates.generate_query_no_context(
                input=input,
                output=output,
                few_shot_examples=self._few_shot_examples_no_context,
            )
        else:
            llm_query = templates.generate_query_with_context(
                input=input,
                output=output,
                context=context,
                few_shot_examples=self._few_shot_examples_with_context,
            )

        return llm_query

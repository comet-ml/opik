import asyncio
import logging
from typing import Optional, Union, Any, List, Dict

import pydantic

import opik.exceptions as exceptions
from opik.evaluation.models import base_model, models_factory
from . import schema, templates
from .. import (
    types as conversation_types,
    conversation_thread_metric,
    helpers,
)
from ... import score_result
from ...llm_judges import parsing_helpers

LOGGER = logging.getLogger(__name__)


class ConversationalCoherenceMetric(
    conversation_thread_metric.ConversationThreadMetric
):
    """
    Calculates the conversational coherence metric for a given conversation thread.
    This metric assesses the coherence and relevance across a series of conversation
    turns by evaluating the consistency in responses, logical flow, and overall
    context maintenance. It evaluates whether the conversation session felt like a
    natural, adaptive, helpful interaction.

    The ``ConversationalCoherenceMetric`` builds a sliding window of dialogue turns for
    each turn in the conversation. It then uses a language model to evaluate whether
    the final `assistant` message within each window is relevant and coherent in
    relation to the preceding conversational context.

    It supports both synchronous and asynchronous operations to
    accommodate the model's operation type. It returns a score between `0.0` and `1.0`,
    where `0.0` indicates a low coherence score and `1.0` indicates a high coherence score.

    Args:
        model: The model to use for
            evaluating the conversation. If a string is provided, it will be used to
            fetch the model from the LiteLLM API. If a base_model.OpikBaseModel is
            provided, it will be used directly. Default is None.
        name: The name of the metric. The default is "conversational_coherence_score".
        include_reason: Whether to include the reason for the score in the
            result. Default is True.
        track: Whether to track the metric. Default is True.
        project_name: The name of the project to track the metric in.
            Default is None.
        window_size: The window size to use for calculating the score. It defines the
            maximal number of historical turns to include in each window when assessing
            the coherence of the current turn in the conversation. Default is 10.

    Example:
        >>> from opik.evaluation.metrics import ConversationalCoherenceMetric
        >>> conversation = [
        >>>     {"role": "user", "content": "Hello!"},
        >>>     {"role": "assistant", "content": "Hi there!"},
        >>>     {"role": "user", "content": "How are you?"},
        >>>     {"role": "assistant", "content": "I'm doing well, thank you!"},
        >>> ]
        >>> metric = ConversationalCoherenceMetric()
        >>> result = metric.score(conversation)
        >>> if result.scoring_failed:
        >>>     print(f"Scoring failed: {result.reason}")
        >>> else:
        >>>     print(result.value)
    """

    def __init__(
        self,
        model: Optional[Union[str, base_model.OpikBaseModel]] = None,
        name: str = "conversational_coherence_score",
        include_reason: bool = True,
        track: bool = True,
        project_name: Optional[str] = None,
        window_size: int = 10,
    ):
        super().__init__(
            name=name,
            track=track,
            project_name=project_name,
        )
        self._init_model(model)
        self._include_reason = include_reason
        self._window_size = window_size

    def _init_model(
        self, model: Optional[Union[str, base_model.OpikBaseModel]]
    ) -> None:
        if isinstance(model, base_model.OpikBaseModel):
            self._model = model
        else:
            self._model = models_factory.get(model_name=model)

    def score(
        self,
        conversation: conversation_types.Conversation,
        **ignored_kwargs: Any,
    ) -> score_result.ScoreResult:
        return self._calculate_score(conversation=conversation)

    async def ascore(
        self,
        conversation: conversation_types.Conversation,
        **ignored_kwargs: Any,
    ) -> score_result.ScoreResult:
        return await self._a_calculate_score(conversation=conversation)

    def _calculate_score(
        self,
        conversation: conversation_types.Conversation,
    ) -> score_result.ScoreResult:
        try:
            turns_windows = helpers.extract_turns_windows_from_conversation(
                conversation=conversation, window_size=self._window_size
            )

            verdicts = [
                self._evaluate_conversation(conversation_sliding_window=window)
                for window in turns_windows
            ]

            score = _score_from_verdicts(verdicts=verdicts)
            reason = (
                self._reason_from_verdicts(score=score, verdicts=verdicts)
                if self._include_reason
                else None
            )
            return score_result.ScoreResult(
                name=self.name,
                value=score,
                reason=reason,
            )
        except Exception as e:
            LOGGER.error(f"Failed to calculate conversational coherence score: {e}")
            raise exceptions.MetricComputationError(
                f"Failed to calculate conversational coherence score: {e}"
            ) from e

    async def _a_calculate_score(
        self,
        conversation: conversation_types.Conversation,
    ) -> score_result.ScoreResult:
        try:
            turns_windows = helpers.extract_turns_windows_from_conversation(
                conversation=conversation, window_size=self._window_size
            )

            verdicts = await asyncio.gather(
                *[
                    self._a_evaluate_conversation(conversation_sliding_window=window)
                    for window in turns_windows
                ]
            )

            score = _score_from_verdicts(verdicts=verdicts)
            reason = (
                await self._a_reason_from_verdicts(score=score, verdicts=verdicts)
                if self._include_reason
                else None
            )
            return score_result.ScoreResult(
                name=self.name,
                value=score,
                reason=reason,
            )
        except Exception as e:
            LOGGER.error(f"Failed to calculate conversational coherence score: {e}")
            raise exceptions.MetricComputationError(
                f"Failed to calculate conversational coherence score: {e}"
            )

    def _reason_from_verdicts(
        self, score: float, verdicts: List[schema.EvaluateConversationCoherenceResponse]
    ) -> str:
        irrelevancies: List[Dict[str, str]] = _extract_irrelevancies_from_verdicts(
            verdicts
        )
        llm_query = templates.generate_reason(score=score, irrelevancies=irrelevancies)
        model_output = self._model.generate_string(
            input=llm_query, response_format=schema.ScoreReasonResponse
        )
        return _generate_reason_from_model_output(model_output=model_output)

    async def _a_reason_from_verdicts(
        self, score: float, verdicts: List[schema.EvaluateConversationCoherenceResponse]
    ) -> str:
        irrelevancies: List[Dict[str, str]] = _extract_irrelevancies_from_verdicts(
            verdicts
        )
        llm_query = templates.generate_reason(score=score, irrelevancies=irrelevancies)
        model_output = await self._model.agenerate_string(
            input=llm_query, response_format=schema.ScoreReasonResponse
        )
        return _generate_reason_from_model_output(model_output=model_output)

    def _evaluate_conversation(
        self, conversation_sliding_window: conversation_types.Conversation
    ) -> schema.EvaluateConversationCoherenceResponse:
        llm_query = templates.evaluate_conversation(
            sliding_window=conversation_sliding_window
        )

        model_output = self._model.generate_string(
            input=llm_query,
            response_format=schema.EvaluateConversationCoherenceResponse,
        )
        return _evaluate_conversation_from_model_output(model_output=model_output)

    async def _a_evaluate_conversation(
        self, conversation_sliding_window: conversation_types.Conversation
    ) -> schema.EvaluateConversationCoherenceResponse:
        llm_query = templates.evaluate_conversation(
            sliding_window=conversation_sliding_window
        )

        model_output = await self._model.agenerate_string(
            input=llm_query,
            response_format=schema.EvaluateConversationCoherenceResponse,
        )
        return _evaluate_conversation_from_model_output(model_output=model_output)


def _generate_reason_from_model_output(model_output: str) -> str:
    try:
        dict_content = parsing_helpers.extract_json_content_or_raise(model_output)
        return schema.ScoreReasonResponse.model_validate(dict_content).reason
    except pydantic.ValidationError as e:
        LOGGER.warning(
            f"Failed to parse coherence score reason from the LLM output: {model_output}, reason: {e}",
            exc_info=True,
        )
        raise e


def _extract_irrelevancies_from_verdicts(
    verdicts: List[schema.EvaluateConversationCoherenceResponse],
) -> List[Dict[str, str]]:
    return [
        {"message_number": f"{index + 1}", "reason": verdict.reason}
        for index, verdict in enumerate(verdicts)
        if verdict.verdict.strip().lower() == "no" and verdict.reason is not None
    ]


def _evaluate_conversation_from_model_output(
    model_output: str,
) -> schema.EvaluateConversationCoherenceResponse:
    try:
        dict_content = parsing_helpers.extract_json_content_or_raise(model_output)
        return schema.EvaluateConversationCoherenceResponse.model_validate(dict_content)
    except pydantic.ValidationError as e:
        LOGGER.warning(
            f"Failed to parse conversation coherence evaluation results from the LLM output: {model_output}, reason: {e}",
            exc_info=True,
        )
        raise e


def _score_from_verdicts(
    verdicts: List[schema.EvaluateConversationCoherenceResponse],
) -> float:
    if len(verdicts) == 0:
        return 0.0

    relevant_count = sum(v.verdict.strip().lower() != "no" for v in verdicts)
    return relevant_count / len(verdicts)

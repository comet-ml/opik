import asyncio
import logging
from typing import Optional, Union, Any, List, Dict

import pydantic

from opik import exceptions
from . import schema, templates
from ... import score_result
from .. import (
    types as conversation_types,
    conversation_thread_metric,
    helpers,
    conversation_turns_factory,
)
from opik.evaluation.models import base_model, models_factory
from ...llm_judges import parsing_helpers

LOGGER = logging.getLogger(__name__)


class ConversationalCoherenceMetric(
    conversation_thread_metric.ConversationThreadMetric
):
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
            turns_windows = _extract_turns_windows_from_conversation(
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
            turns_windows = _extract_turns_windows_from_conversation(
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


def _extract_turns_windows_from_conversation(
    conversation: conversation_types.Conversation, window_size: int
) -> List[conversation_types.Conversation]:
    if len(conversation) == 0:
        raise ValueError("Conversation is empty")

    turns = conversation_turns_factory.build_conversation_turns(
        conversation=conversation
    )
    if len(turns) == 0:
        raise ValueError("Conversation has no turns")

    turns_windows: List[conversation_types.Conversation] = [
        helpers.merge_turns(turns_window)
        for turns_window in helpers.get_turns_in_sliding_window(turns, window_size)
    ]

    return turns_windows


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

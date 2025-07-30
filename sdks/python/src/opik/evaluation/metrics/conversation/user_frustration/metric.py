import asyncio
import logging
from typing import Optional, Union, Any, List, Dict

import pydantic

import opik.exceptions as exceptions
from opik.evaluation.metrics import score_result
from opik.evaluation.models import base_model, models_factory
from . import schema, templates
from .. import (
    types as conversation_types,
    conversation_thread_metric,
    helpers,
)
from ...llm_judges import parsing_helpers

LOGGER = logging.getLogger(__name__)


class UserFrustrationMetric(conversation_thread_metric.ConversationThreadMetric):
    """
    A heuristic score estimating the likelihood that the user experienced confusion, annoyance,
    or disengagement during the session — due to repetition, lack of adaptation, ignored
    intent signals, or failure to smoothly conclude.

    The ``UserFrustrationMetric`` class integrates with LLM models to analyze
    conversation data in sliding windows and produce a numerical score along with an optional
    reason for the calculated score. It provides both synchronous and asynchronous methods for
    calculation and supports customization through attributes like window size and reason inclusion.

    This metric can be used to monitor and track user frustration levels during conversations, enabling
    insights into user experience. The metric makes use of LLM models to score conversational
    windows and summarize results. It returns a score between `0.0` and `1.0`. The higher the score,
    the more frustrated the user is likely to be.

    Args:
        model: The model to use for evaluating the conversation. If a string is provided, it will be used to
            fetch the model from the LiteLLM API. If a base_model.OpikBaseModel is
            provided, it will be used directly. Default is None.
        name: The name of the metric. The default is "user_frustration_score".
        include_reason: Whether to include the reason for the score in the
            result. Default is True.
        track: Whether to track the metric. Default is True.
        project_name: The name of the project to track the metric in.
            Default is None.
        window_size: The window size to use for calculating the score. It defines the
            maximal number of historical turns to include in each window when assessing
            the frustration of the current turn in the conversation. Default is 10.

    Example:
        >>> from opik.evaluation.metrics import UserFrustrationMetric
        >>> conversation = [
        >>>     {"role": "user", "content": "How do I center a div using CSS?"},
        >>>     {"role": "assistant", "content": "There are many ways to center elements in CSS."},
        >>>     {"role": "user", "content": "Okay... can you show me one?"},
        >>>     {"role": "assistant", "content": "Sure. It depends on the context — are you centering horizontally, vertically, or both?"},
        >>>     {"role": "user", "content": "Both. Just give me a basic example."},
        >>> ]
        >>> metric = UserFrustrationMetric()
        >>> result = metric.score(conversation)
        >>> if result.scoring_failed:
        >>>     print(f"Scoring failed: {result.reason}")
        >>> else:
        >>>     print(result.value)
    """

    def __init__(
        self,
        model: Optional[Union[str, base_model.OpikBaseModel]] = None,
        name: str = "user_frustration_score",
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
            LOGGER.error(f"Failed to calculate user frustration score: {e}")
            raise exceptions.MetricComputationError(
                f"Failed to calculate user frustration score: {e}"
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
            LOGGER.error(f"Failed to calculate user frustration score: {e}")
            raise exceptions.MetricComputationError(
                f"Failed to calculate user frustration score: {e}"
            ) from e

    def _evaluate_conversation(
        self,
        conversation_sliding_window: conversation_types.Conversation,
    ) -> schema.EvaluateUserFrustrationResponse:
        llm_query = templates.evaluate_conversation(
            sliding_window=conversation_sliding_window
        )
        model_output = self._model.generate_string(
            input=llm_query,
            response_format=schema.EvaluateUserFrustrationResponse,
        )
        return _evaluate_conversation_from_model_output(model_output=model_output)

    async def _a_evaluate_conversation(
        self,
        conversation_sliding_window: conversation_types.Conversation,
    ) -> schema.EvaluateUserFrustrationResponse:
        llm_query = templates.evaluate_conversation(
            sliding_window=conversation_sliding_window
        )
        model_output = await self._model.agenerate_string(
            input=llm_query,
            response_format=schema.EvaluateUserFrustrationResponse,
        )
        return _evaluate_conversation_from_model_output(model_output=model_output)

    def _reason_from_verdicts(
        self, score: float, verdicts: List[schema.EvaluateUserFrustrationResponse]
    ) -> str:
        frustrations: List[Dict[str, str]] = _extract_frustrations_from_verdicts(
            verdicts
        )

        llm_query = templates.generate_reason(score=score, frustrations=frustrations)

        model_output = self._model.generate_string(
            input=llm_query, response_format=schema.ScoreReasonResponse
        )
        return _generate_reason_from_model_output(model_output=model_output)

    async def _a_reason_from_verdicts(
        self, score: float, verdicts: List[schema.EvaluateUserFrustrationResponse]
    ) -> str:
        frustrations: List[Dict[str, str]] = _extract_frustrations_from_verdicts(
            verdicts
        )

        llm_query = templates.generate_reason(score=score, frustrations=frustrations)

        model_output = await self._model.agenerate_string(
            input=llm_query, response_format=schema.ScoreReasonResponse
        )
        return _generate_reason_from_model_output(model_output=model_output)


def _evaluate_conversation_from_model_output(
    model_output: str,
) -> schema.EvaluateUserFrustrationResponse:
    try:
        dict_content = parsing_helpers.extract_json_content_or_raise(model_output)
        return schema.EvaluateUserFrustrationResponse.model_validate(dict_content)
    except pydantic.ValidationError as e:
        LOGGER.warning(
            f"Failed to parse user's frustration evaluation results from the LLM output: {model_output}, reason: {e}",
            exc_info=True,
        )
        raise e


def _score_from_verdicts(
    verdicts: List[schema.EvaluateUserFrustrationResponse],
) -> float:
    if len(verdicts) == 0:
        return 0.0

    frustrated_count = sum(v.verdict.strip().lower() == "yes" for v in verdicts)
    return frustrated_count / len(verdicts)


def _extract_frustrations_from_verdicts(
    verdicts: List[schema.EvaluateUserFrustrationResponse],
) -> List[Dict[str, str]]:
    return [
        {"message_number": f"{index + 1}", "reason": verdict.reason}
        for index, verdict in enumerate(verdicts)
        if verdict.verdict.strip().lower() == "yes" and verdict.reason is not None
    ]


def _generate_reason_from_model_output(model_output: str) -> str:
    try:
        dict_content = parsing_helpers.extract_json_content_or_raise(model_output)
        return schema.ScoreReasonResponse.model_validate(dict_content).reason
    except pydantic.ValidationError as e:
        LOGGER.warning(
            f"Failed to parse frustration score reason from the LLM output: {model_output}, reason: {e}",
            exc_info=True,
        )
        raise e

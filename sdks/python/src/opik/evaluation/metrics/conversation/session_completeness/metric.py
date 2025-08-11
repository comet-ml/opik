import asyncio
import logging
from typing import Optional, Any, Union, List
import pydantic

import opik.exceptions as exceptions
from opik.evaluation.models import base_model, models_factory
from . import schema, templates
from .. import conversation_thread_metric, types as conversation_types
from ... import score_result
from ...llm_judges import parsing_helpers

LOGGER = logging.getLogger(__name__)


class SessionCompletenessQuality(conversation_thread_metric.ConversationThreadMetric):
    """
    Represents the Session Completeness Quality metric for a conversation thread.

    This class is a specific implementation of the ``ConversationThreadMetric`` that
    evaluates the completeness of a session within a conversation thread. The
    metric is used to judge how well a session addresses the intended context or
    purpose of the conversation.

    The process begins by using an LLM to extract a list of high-level user
    intentions from the conversation turns. The same LLM is then used to assess
    whether each intention was addressed and/or fulfilled over the course of
    the conversation. It returns a score between `0.0` and `1.0`, where higher values
    indicate better session completeness.

    Args:
        model: The language model to use for evaluation.
        name: The name of the metric. The default is "session_completeness_quality".
        include_reason: Whether to include a reason for the score.
        track: Whether to track the metric. Default is True.
        project_name: The project name to track the metric in.

    Example:
        >>> from opik.evaluation.metrics import SessionCompletenessQuality
        >>> conversation = [
        >>>     {"role": "user", "content": "Hello!"},
        >>>     {"role": "assistant", "content": "Hi there!"},
        >>>     {"role": "user", "content": "How are you?"},
        >>>     {"role": "assistant", "content": "I'm doing well, thank you!"},
        >>> ]
        >>> metric = SessionCompletenessQuality()
        >>> result = metric.score(conversation)
        >>> if result.scoring_failed:
        >>>     print(f"Scoring failed: {result.reason}")
        >>> else:
        >>>     print(result.value)
        0.95
    """

    def __init__(
        self,
        model: Optional[Union[str, base_model.OpikBaseModel]] = None,
        name: str = "session_completeness_quality",
        include_reason: bool = True,
        track: bool = True,
        project_name: Optional[str] = None,
    ):
        super().__init__(
            name=name,
            track=track,
            project_name=project_name,
        )
        self._init_model(model)
        self._include_reason = include_reason

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

    def _extract_user_goals(
        self,
        conversation: conversation_types.Conversation,
    ) -> List[str]:
        llm_query = templates.extract_user_goals(conversation)
        model_output = self._model.generate_string(
            input=llm_query, response_format=schema.UserGoalsResponse
        )
        return _extract_user_goals_from_model_output(model_output=model_output)

    def _evaluate_user_goal(
        self, conversation: conversation_types.Conversation, user_goal: str
    ) -> schema.EvaluateUserGoalResponse:
        llm_query = templates.evaluate_user_goal(
            conversation=conversation, user_goal=user_goal
        )
        model_output = self._model.generate_string(
            input=llm_query, response_format=schema.EvaluateUserGoalResponse
        )
        return _evaluate_user_goal_from_model_output(model_output=model_output)

    def _generate_reason(
        self,
        score: float,
        verdicts: List[schema.EvaluateUserGoalResponse],
        user_goals: List[str],
    ) -> Optional[str]:
        if not self._include_reason:
            return None

        negative_verdicts = _extract_negative_verdicts(verdicts)

        llm_query = templates.generate_reason(
            score=score, negative_verdicts=negative_verdicts, user_goals=user_goals
        )
        model_output = self._model.generate_string(
            input=llm_query, response_format=schema.ScoreReasonResponse
        )
        return _generate_reason_from_model_output(model_output=model_output)

    def _calculate_score(
        self,
        conversation: conversation_types.Conversation,
    ) -> score_result.ScoreResult:
        try:
            if len(conversation) == 0:
                raise ValueError("Conversation is empty")

            user_goals = self._extract_user_goals(conversation)
            verdicts = [
                self._evaluate_user_goal(conversation=conversation, user_goal=user_goal)
                for user_goal in user_goals
            ]

            score = _score_from_verdicts(verdicts=verdicts)
            reason = self._generate_reason(
                score=score, verdicts=verdicts, user_goals=user_goals
            )

            return score_result.ScoreResult(name=self.name, value=score, reason=reason)
        except Exception as e:
            LOGGER.error(f"Failed to calculate session completeness quality: {e}")
            raise exceptions.MetricComputationError(
                f"Failed to calculate session completeness quality: {e}"
            ) from e

    async def _a_extract_user_goals(
        self,
        conversation: conversation_types.Conversation,
    ) -> List[str]:
        llm_query = templates.extract_user_goals(conversation)
        model_output = await self._model.agenerate_string(
            input=llm_query, response_format=schema.UserGoalsResponse
        )
        return _extract_user_goals_from_model_output(model_output=model_output)

    async def _a_evaluate_user_goal(
        self, conversation: conversation_types.Conversation, user_goal: str
    ) -> schema.EvaluateUserGoalResponse:
        llm_query = templates.evaluate_user_goal(
            conversation=conversation, user_goal=user_goal
        )
        model_output = await self._model.agenerate_string(
            input=llm_query, response_format=schema.EvaluateUserGoalResponse
        )
        return _evaluate_user_goal_from_model_output(model_output=model_output)

    async def _a_generate_reason(
        self,
        score: float,
        verdicts: List[schema.EvaluateUserGoalResponse],
        user_goals: List[str],
    ) -> Optional[str]:
        if not self._include_reason:
            return None

        negative_verdicts = _extract_negative_verdicts(verdicts)

        llm_query = templates.generate_reason(
            score=score, negative_verdicts=negative_verdicts, user_goals=user_goals
        )
        model_output = await self._model.agenerate_string(
            input=llm_query, response_format=schema.ScoreReasonResponse
        )
        return _generate_reason_from_model_output(model_output=model_output)

    async def _a_calculate_score(
        self,
        conversation: conversation_types.Conversation,
    ) -> score_result.ScoreResult:
        try:
            if len(conversation) == 0:
                raise ValueError("Conversation is empty")

            user_goals = await self._a_extract_user_goals(conversation)
            verdicts = await asyncio.gather(
                *[
                    self._a_evaluate_user_goal(
                        conversation=conversation, user_goal=user_goal
                    )
                    for user_goal in user_goals
                ]
            )

            score = _score_from_verdicts(verdicts=verdicts)
            reason = await self._a_generate_reason(
                score=score, verdicts=verdicts, user_goals=user_goals
            )

            return score_result.ScoreResult(name=self.name, value=score, reason=reason)
        except Exception as e:
            LOGGER.warning(
                f"Failed to calculate session completeness quality: {e}", exc_info=True
            )
            raise exceptions.MetricComputationError(
                f"Failed to calculate session completeness quality: {e}"
            ) from e


def _generate_reason_from_model_output(
    model_output: str,
) -> str:
    try:
        dict_content = parsing_helpers.extract_json_content_or_raise(model_output)
        return schema.ScoreReasonResponse.model_validate(dict_content).reason
    except pydantic.ValidationError as e:
        LOGGER.warning(
            f"Failed to parse reason from LLM output: {model_output}, reason: {e}",
            exc_info=True,
        )
        raise e


def _evaluate_user_goal_from_model_output(
    model_output: str,
) -> schema.EvaluateUserGoalResponse:
    try:
        dict_content = parsing_helpers.extract_json_content_or_raise(model_output)
        return schema.EvaluateUserGoalResponse.model_validate(dict_content)
    except pydantic.ValidationError as e:
        LOGGER.warning(
            f"Failed to parse user goal evaluation results from LLM output: {model_output}, reason: {e}",
            exc_info=True,
        )
        raise e


def _extract_user_goals_from_model_output(
    model_output: str,
) -> List[str]:
    try:
        dict_content = parsing_helpers.extract_json_content_or_raise(model_output)
        return schema.UserGoalsResponse.model_validate(dict_content).user_goals
    except pydantic.ValidationError as e:
        LOGGER.warning(
            f"Failed to parse user goals from LLM output: {model_output}, reason: {e}",
            exc_info=True,
        )
        raise e


def _extract_negative_verdicts(
    verdicts: List[schema.EvaluateUserGoalResponse],
) -> List[str]:
    return [
        v.reason
        for v in verdicts
        if v.verdict.strip().lower() == "no" and v.reason is not None
    ]


def _score_from_verdicts(verdicts: List[schema.EvaluateUserGoalResponse]) -> float:
    if len(verdicts) == 0:
        return 0.0

    relevant_count = sum(v.verdict.strip().lower() != "no" for v in verdicts)
    return relevant_count / len(verdicts)

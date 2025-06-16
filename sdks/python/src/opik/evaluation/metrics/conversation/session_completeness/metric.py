import logging
from typing import Optional, Any, Union, List

import pydantic

from opik.api_objects.conversation import conversation_thread
from opik.evaluation.models import base_model, models_factory
from . import schema, templates
from .. import conversation_thread_metric
from ... import score_result


LOGGER = logging.getLogger(__name__)


class SessionCompletenessQuality(conversation_thread_metric.ConversationThreadMetric):
    """
    Represents the Session Completeness Quality metric for a conversation thread.

    This class is a specific implementation of the ConversationThreadMetric that
    evaluates the completeness of a session within a conversation thread. The
    metric is used to judge how well a session addresses the intended context or
    purpose of the conversation.

    The process begins by using an LLM to extract a list of high-level user
    intentions from the conversation turns. The same LLM is then used to assess
    whether each intention was addressed and/or fulfilled over the course of
    the conversation. It returns a score between 0.0 and 1.0, where higher values
    indicate better session completeness.

    Args:
        model (str | opik.evaluation.models.OpikBaseModel): The language model to use for evaluation.
        name (str): The name of the metric. Default is "session_completeness_quality".
        track (bool): Whether to track the metric. Default is True.
        project_name (str, optional): The project name to track the metric in.
    """

    def __init__(
        self,
        model: Optional[Union[str, base_model.OpikBaseModel]] = None,
        name: str = "session_completeness_quality",
        track: bool = True,
        project_name: Optional[str] = None,
    ):
        super().__init__(
            name=name,
            track=track,
            project_name=project_name,
        )
        self._init_model(model)

    def _init_model(
        self, model: Optional[Union[str, base_model.OpikBaseModel]]
    ) -> None:
        if isinstance(model, base_model.OpikBaseModel):
            self._model = model
        else:
            self._model = models_factory.get(model_name=model)

    def score(
        self,
        thread: conversation_thread.ConversationThread,
        **ignored_kwargs: Any,
    ) -> score_result.ScoreResult:
        return self._calculate_score(thread=thread)

    def _extract_user_goals(
        self, thread: conversation_thread.ConversationThread
    ) -> List[str]:
        llm_query = templates.extract_user_goals(thread=thread)
        model_output = self._model.generate_string(
            input=llm_query, response_format=schema.UserGoalsResponse
        )
        try:
            return schema.UserGoalsResponse.model_validate_json(model_output).goals
        except pydantic.ValidationError as e:
            LOGGER.warning(
                f"Failed to parse user goals from LLM output: {model_output}, reason: {e}"
            )
            return []

    def _evaluate_user_goal(
        self, thread: conversation_thread.ConversationThread, user_goal: str
    ) -> Optional[schema.EvaluateUserGoalResponse]:
        llm_query = templates.evaluate_user_goal(thread=thread, user_goal=user_goal)
        model_output = self._model.generate_string(
            input=llm_query, response_format=schema.EvaluateUserGoalResponse
        )
        try:
            return schema.EvaluateUserGoalResponse.model_validate_json(model_output)
        except pydantic.ValidationError as e:
            LOGGER.warning(
                f"Failed to parse user goal evaluation results from LLM output: {model_output}, reason: {e}"
            )
            return None

    def _calculate_score(
        self, thread: conversation_thread.ConversationThread
    ) -> score_result.ScoreResult:
        user_goals = self._extract_user_goals(thread=thread)
        verdicts = [
            self._evaluate_user_goal(thread=thread, user_goal=user_goal)
            for user_goal in user_goals
        ]
        relevant_count = sum(
            v.verdict.strip().lower() != "no" for v in verdicts if v is not None
        )
        if relevant_count == 0:
            return score_result.ScoreResult(name=self.name, value=0.0)

        score = relevant_count / len(verdicts)
        return score_result.ScoreResult(name=self.name, value=score)

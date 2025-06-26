import logging
from typing import Optional, Union, Any, List, Dict

import pydantic

from opik import exceptions
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

    relevant_count = sum(v.verdict.strip().lower() != "yes" for v in verdicts)
    return relevant_count / len(verdicts)


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

import datetime
import logging
from typing import List, Dict, Optional, Any, Union

from opik.types import ErrorInfoDict, SpanType
from . import models, emulator_message_processor
from .. import messages
from ...rest_api.types import span_write, trace_write


LOGGER = logging.getLogger(__name__)


class LocalEmulatorMessageProcessor(
    emulator_message_processor.EmulatorMessageProcessor
):
    """This class serves as a replacement for the real backend and collects all logged messages
    locally in memory to be used for evaluation.
    """

    def __init__(self, active: bool, merge_duplicates: bool = True) -> None:
        super().__init__(active=active, merge_duplicates=merge_duplicates)

    def process(
        self,
        message: Union[
            messages.BaseMessage, span_write.SpanWrite, trace_write.TraceWrite
        ],
    ) -> None:
        if not self.is_active():
            return

        if hasattr(message, "delivery_attempts") and message.delivery_attempts > 1:
            # skip retries
            LOGGER.debug("Skipping retry of the message: %s", message)
            return

        super().process(message)

    def create_trace_model(
        self,
        trace_id: str,
        start_time: datetime.datetime,
        name: Optional[str],
        project_name: str,
        input: Any,
        output: Any,
        tags: Optional[List[str]],
        metadata: Optional[Dict[str, Any]],
        end_time: Optional[datetime.datetime],
        spans: Optional[List[models.SpanModel]],
        feedback_scores: Optional[List[models.FeedbackScoreModel]],
        error_info: Optional[ErrorInfoDict],
        thread_id: Optional[str],
        last_updated_at: Optional[datetime.datetime] = None,
    ) -> models.TraceModel:
        if spans is None:
            spans = []
        if feedback_scores is None:
            feedback_scores = []

        return models.TraceModel(
            id=trace_id,
            start_time=start_time,
            name=name,
            project_name=project_name,
            input=input,
            output=output,
            tags=tags,
            metadata=metadata,
            end_time=end_time,
            spans=spans,
            feedback_scores=feedback_scores,
            error_info=error_info,
            thread_id=thread_id,
            last_updated_at=last_updated_at,
        )

    def create_span_model(
        self,
        span_id: str,
        start_time: datetime.datetime,
        name: Optional[str],
        input: Any,
        output: Any,
        tags: Optional[List[str]],
        metadata: Optional[Dict[str, Any]],
        type: SpanType,
        usage: Optional[Dict[str, Any]],
        end_time: Optional[datetime.datetime],
        project_name: str,
        spans: Optional[List[models.SpanModel]],
        feedback_scores: Optional[List[models.FeedbackScoreModel]],
        model: Optional[str],
        provider: Optional[str],
        error_info: Optional[ErrorInfoDict],
        total_cost: Optional[float],
        last_updated_at: Optional[datetime.datetime],
    ) -> models.SpanModel:
        if spans is None:
            spans = []
        if feedback_scores is None:
            feedback_scores = []

        return models.SpanModel(
            id=span_id,
            start_time=start_time,
            name=name,
            input=input,
            output=output,
            tags=tags,
            metadata=metadata,
            type=type,
            usage=usage,
            end_time=end_time,
            project_name=project_name,
            spans=spans,
            feedback_scores=feedback_scores,
            model=model,
            provider=provider,
            error_info=error_info,
            total_cost=total_cost,
            last_updated_at=last_updated_at,
        )

    def create_feedback_score_model(
        self,
        score_id: str,
        name: str,
        value: float,
        category_name: Optional[str],
        reason: Optional[str],
    ) -> models.FeedbackScoreModel:
        return models.FeedbackScoreModel(
            id=score_id,
            name=name,
            value=value,
            category_name=category_name,
            reason=reason,
        )

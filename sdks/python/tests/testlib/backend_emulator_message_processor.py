import datetime
from typing import List, Dict, Optional, Any

from opik.message_processing.emulation import emulator_message_processor
from opik.types import ErrorInfoDict, SpanType
from . import models


class BackendEmulatorMessageProcessor(
    emulator_message_processor.EmulatorMessageProcessor
):
    """
    This class serves as a replacement for the real backend. It collects all logged messages
    to be used in tests.
    """

    def __init__(self, active: bool = True, merge_duplicates: bool = True) -> None:
        super().__init__(active=active, merge_duplicates=merge_duplicates)
        self._handlers[messages.CreateAttachmentMessage] = (
            self._handle_create_attachment_message
        )
        self._handlers[messages.AttachmentSupportingMessage] = (
            self._handle_attachment_supporting_message
        )

    def _handle_create_attachment_message(
        self, message: messages.CreateAttachmentMessage
    ) -> None:
        with self._rlock:
            if message.entity_id in self._span_observations:
                span = self._span_observations[message.entity_id]
                span.attachments.append(message.as_payload_dict())
                return

            if message.entity_id in self._trace_observations:
                trace = self._trace_observations[message.entity_id]
                # TraceModel in models.py does NOT have attachments field yet?
                # Check models.py. It has metadata, input, output.
                # If TraceModel doesn't have it, we might ignore or add it dynamically?
                # For now let's focus on Spans as requested by TTS feature.
                pass

    def _handle_attachment_supporting_message(
        self, message: messages.AttachmentSupportingMessage
    ) -> None:
        self._dispatch_message(message.original_message)

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
        attachments: Optional[List[Dict[str, Any]]] = None,
    ) -> models.SpanModel:
        if spans is None:
            spans = []
        if feedback_scores is None:
            feedback_scores = []
        if attachments is None: # Accept attachments argument
            attachments = []

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
            attachments=attachments,
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

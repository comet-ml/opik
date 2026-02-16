import datetime
from typing import List, Dict, Optional, Any, TYPE_CHECKING

from opik.message_processing.emulation import emulator_message_processor
from opik.types import ErrorInfoDict, SpanType
from . import models

if TYPE_CHECKING:
    from . import noop_file_upload_manager


class BackendEmulatorMessageProcessor(
    emulator_message_processor.EmulatorMessageProcessor
):
    """
    This class serves as a replacement for the real backend. It collects all logged messages
    to be used in tests.

    Optionally accepts a file_upload_manager to access attachment data that was
    intercepted by the FileUploadPreprocessor before reaching the message processor.
    """

    def __init__(
        self,
        active: bool = True,
        merge_duplicates: bool = True,
        file_upload_manager: Optional[
            "noop_file_upload_manager.FileUploadManagerEmulator"
        ] = None,
    ) -> None:
        super().__init__(active=active, merge_duplicates=merge_duplicates)
        self._file_upload_manager = file_upload_manager

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
        ttft: Optional[float] = None,
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
            ttft=ttft,
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

    @property
    def trace_trees(self) -> List[models.TraceModel]:
        """
        Override to add attachments from the file upload manager.

        Attachments are intercepted by FileUploadPreprocessor before reaching
        the message processor, so we need to get them from the upload manager.
        """
        # Get base trace trees from parent
        traces = super().trace_trees

        # If we have a file upload manager, add attachments to spans and traces
        if self._file_upload_manager is not None:
            self._add_attachments_to_traces(traces)

        return traces

    def _add_attachments_to_traces(self, traces: List[models.TraceModel]) -> None:
        """Add attachments from file upload manager to traces and their spans."""
        for trace in traces:
            # Add trace-level attachments
            trace_attachments = self._file_upload_manager.attachments_by_trace.get(
                trace.id, []
            )
            if trace_attachments:
                trace.attachments = [
                    models.AttachmentModel(
                        file_path=att.file_path,
                        file_name=att.file_name,
                        content_type=att.mime_type or "",
                    )
                    for att in trace_attachments
                ]

            # Add span-level attachments recursively
            self._add_attachments_to_spans(trace.spans)

    def _add_attachments_to_spans(self, spans: List[models.SpanModel]) -> None:
        """Recursively add attachments to spans."""
        for span in spans:
            span_attachments = self._file_upload_manager.attachments_by_span.get(
                span.id, []
            )
            if span_attachments:
                span.attachments = [
                    models.AttachmentModel(
                        file_path=att.file_path,
                        file_name=att.file_name,
                        content_type=att.mime_type or "",
                    )
                    for att in span_attachments
                ]

            # Recurse into nested spans
            if span.spans:
                self._add_attachments_to_spans(span.spans)

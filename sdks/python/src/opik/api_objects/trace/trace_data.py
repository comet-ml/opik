import dataclasses
import datetime
from typing import Any

import opik.api_objects.attachment as attachment
import opik.datetime_helpers as datetime_helpers
import opik.id_helpers as id_helpers
import opik.llm_usage as llm_usage
from opik.types import (
    CreatedByType,
    ErrorInfoDict,
    FeedbackScoreDict,
    LLMProvider,
    SpanType,
)
from .. import span
from ..observation_data import ObservationData


@dataclasses.dataclass
class TraceData(ObservationData):
    """
    The TraceData object is returned when calling :func:`opik.opik_context.get_current_trace_data` from a tracked function.
    """

    id: str = dataclasses.field(default_factory=id_helpers.generate_id)
    created_by: CreatedByType | None = None
    thread_id: str | None = None

    def create_child_span_data(
        self,
        name: str | None = None,
        type: SpanType = "general",
        start_time: datetime.datetime | None = None,
        end_time: datetime.datetime | None = None,
        metadata: dict[str, Any] | None = None,
        input: dict[str, Any] | None = None,
        output: dict[str, Any] | None = None,
        tags: list[str] | None = None,
        usage: dict[str, Any] | llm_usage.OpikUsage | None = None,
        feedback_scores: list[FeedbackScoreDict] | None = None,
        model: str | None = None,
        provider: str | LLMProvider | None = None,
        error_info: ErrorInfoDict | None = None,
        total_cost: float | None = None,
        attachments: list[attachment.Attachment] | None = None,
    ) -> span.SpanData:
        start_time = (
            start_time if start_time is not None else datetime_helpers.local_timestamp()
        )
        return span.SpanData(
            trace_id=self.id,
            parent_span_id=None,
            project_name=self.project_name,
            name=name,
            type=type,
            start_time=start_time,
            end_time=end_time,
            metadata=metadata,
            input=input,
            output=output,
            tags=tags,
            usage=usage,
            feedback_scores=feedback_scores,
            model=model,
            provider=provider,
            error_info=error_info,
            total_cost=total_cost,
            attachments=attachments,
        )

    @property
    def as_start_parameters(self) -> dict[str, Any]:
        """Returns parameters of this trace to be sent to the server when starting a new trace."""
        start_parameters: dict[str, Any] = {
            "id": self.id,
            "start_time": self.start_time,
            "project_name": self.project_name,
        }
        if self.name is not None:
            start_parameters["name"] = self.name
        if self.input is not None:
            start_parameters["input"] = self.input
        if self.metadata is not None:
            start_parameters["metadata"] = self.metadata
        if self.tags is not None:
            start_parameters["tags"] = self.tags

        return start_parameters

    @property
    def as_parameters(self) -> dict[str, Any]:
        """Returns all parameters of this trace to be sent to the server."""
        return {
            "id": self.id,
            "name": self.name,
            "start_time": self.start_time,
            "end_time": self.end_time,
            "metadata": self.metadata,
            "input": self.input,
            "output": self.output,
            "tags": self.tags,
            "feedback_scores": self.feedback_scores,
            "project_name": self.project_name,
            "error_info": self.error_info,
            "thread_id": self.thread_id,
            "attachments": self.attachments,
        }

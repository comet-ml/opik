import dataclasses
import datetime
from typing import Any

import opik.api_objects.attachment as attachment
import opik.datetime_helpers as datetime_helpers
import opik.llm_usage as llm_usage
from opik.types import (
    DistributedTraceHeadersDict,
    ErrorInfoDict,
    FeedbackScoreDict,
    LLMProvider,
    SpanType,
)
from .. import helpers
from ..observation_data import ObservationData


@dataclasses.dataclass
class SpanData(ObservationData):
    """
    The SpanData object is returned when calling :func:`opik.opik_context.get_current_span_data` from a tracked function.
    """

    trace_id: str
    id: str = dataclasses.field(default_factory=helpers.generate_id)
    parent_span_id: str | None = None
    type: SpanType = "general"
    usage: dict[str, Any] | llm_usage.OpikUsage | None = None
    model: str | None = None
    provider: str | LLMProvider | None = None
    total_cost: float | None = None

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
    ) -> "SpanData":
        start_time = (
            start_time if start_time is not None else datetime_helpers.local_timestamp()
        )
        return SpanData(
            trace_id=self.trace_id,
            parent_span_id=self.id,
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
        """Returns parameters of this span to be sent to the server when starting a new span."""
        start_parameters: dict[str, Any] = {
            "id": self.id,
            "start_time": self.start_time,
            "project_name": self.project_name,
            "trace_id": self.trace_id,
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
        """Returns all parameters of this span to be sent to the server."""
        return {
            "trace_id": self.trace_id,
            "id": self.id,
            "parent_span_id": self.parent_span_id,
            "name": self.name,
            "type": self.type,
            "start_time": self.start_time,
            "end_time": self.end_time,
            "metadata": self.metadata,
            "input": self.input,
            "output": self.output,
            "tags": self.tags,
            "usage": self.usage,
            "feedback_scores": self.feedback_scores,
            "project_name": self.project_name,
            "model": self.model,
            "provider": self.provider,
            "error_info": self.error_info,
            "total_cost": self.total_cost,
            "attachments": self.attachments,
        }

    def get_distributed_trace_headers(self) -> DistributedTraceHeadersDict:
        return DistributedTraceHeadersDict(
            opik_trace_id=self.trace_id,
            opik_parent_span_id=self.id,
        )

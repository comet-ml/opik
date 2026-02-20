import dataclasses
import datetime
from typing import Any, Dict, List, Optional, Union

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
    parent_span_id: Optional[str] = None
    type: SpanType = "general"
    usage: Optional[Union[Dict[str, Any], llm_usage.OpikUsage]] = None
    model: Optional[str] = None
    provider: Optional[Union[str, LLMProvider]] = None
    total_cost: Optional[float] = None

    def create_child_span_data(
        self,
        name: Optional[str] = None,
        type: SpanType = "general",
        start_time: Optional[datetime.datetime] = None,
        end_time: Optional[datetime.datetime] = None,
        metadata: Optional[Dict[str, Any]] = None,
        input: Optional[Dict[str, Any]] = None,
        output: Optional[Dict[str, Any]] = None,
        tags: Optional[List[str]] = None,
        usage: Optional[Union[Dict[str, Any], llm_usage.OpikUsage]] = None,
        feedback_scores: Optional[List[FeedbackScoreDict]] = None,
        model: Optional[str] = None,
        provider: Optional[Union[str, LLMProvider]] = None,
        error_info: Optional[ErrorInfoDict] = None,
        total_cost: Optional[float] = None,
        attachments: Optional[List[attachment.Attachment]] = None,
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
    def as_start_parameters(self) -> Dict[str, Any]:
        """Returns parameters of this span to be sent to the server when starting a new span."""
        start_parameters: Dict[str, Any] = {
            "id": self.id,
            "start_time": self.start_time,
            "project_name": self.project_name,
            "trace_id": self.trace_id,
        }

        if self.parent_span_id is not None:
            start_parameters["parent_span_id"] = self.parent_span_id
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
    def as_parameters(self) -> Dict[str, Any]:
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

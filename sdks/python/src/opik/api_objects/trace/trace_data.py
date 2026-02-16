import dataclasses
import datetime
from typing import Any, Dict, List, Optional, Union

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
    created_by: Optional[CreatedByType] = None
    thread_id: Optional[str] = None
    ttft: Optional[float] = None

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
    def as_start_parameters(self) -> Dict[str, Any]:
        """Returns parameters of this trace to be sent to the server when starting a new trace."""
        start_parameters: Dict[str, Any] = {
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
    def as_parameters(self) -> Dict[str, Any]:
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
            "ttft": self.ttft,
        }

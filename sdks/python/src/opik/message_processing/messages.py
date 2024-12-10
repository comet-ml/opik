import dataclasses
import datetime
from typing import Optional, Any, Dict, List
from ..types import UsageDict, SpanType, ErrorInfoDict


@dataclasses.dataclass
class BaseMessage:
    def as_payload_dict(self) -> Dict[str, Any]:
        # we are not using dataclasses.as_dict() here
        # because it will try to deepcopy all object and will fail if there is non-serializable object
        return {**self.__dict__}


@dataclasses.dataclass
class CreateTraceMessage(BaseMessage):
    trace_id: str
    project_name: str
    name: Optional[str]
    start_time: datetime.datetime
    end_time: Optional[datetime.datetime]
    input: Optional[Dict[str, Any]]
    output: Optional[Dict[str, Any]]
    metadata: Optional[Dict[str, Any]]
    tags: Optional[List[str]]
    error_info: Optional[ErrorInfoDict]

    def as_payload_dict(self) -> Dict[str, Any]:
        data = super().as_payload_dict()
        data["id"] = data.pop("trace_id")
        return data


@dataclasses.dataclass
class UpdateTraceMessage(BaseMessage):
    """
    "Not recommended to use. Kept only for low level update operations in public API"
    """

    trace_id: str
    project_name: str
    end_time: Optional[datetime.datetime]
    input: Optional[Dict[str, Any]]
    output: Optional[Dict[str, Any]]
    metadata: Optional[Dict[str, Any]]
    tags: Optional[List[str]]
    error_info: Optional[ErrorInfoDict]


@dataclasses.dataclass
class CreateSpanMessage(BaseMessage):
    span_id: str
    trace_id: str
    project_name: str
    parent_span_id: Optional[str]
    name: Optional[str]
    start_time: datetime.datetime
    end_time: Optional[datetime.datetime]
    input: Optional[Dict[str, Any]]
    output: Optional[Dict[str, Any]]
    metadata: Optional[Dict[str, Any]]
    tags: Optional[List[str]]
    type: SpanType
    usage: Optional[UsageDict]
    model: Optional[str]
    provider: Optional[str]
    error_info: Optional[ErrorInfoDict]

    def as_payload_dict(self) -> Dict[str, Any]:
        data = super().as_payload_dict()
        data["id"] = data.pop("span_id")
        return data


@dataclasses.dataclass
class UpdateSpanMessage(BaseMessage):
    "Not recommended to use. Kept only for low level update operations in public API"

    span_id: str
    parent_span_id: Optional[str]
    trace_id: str
    project_name: str
    end_time: Optional[datetime.datetime]
    input: Optional[Dict[str, Any]]
    output: Optional[Dict[str, Any]]
    metadata: Optional[Dict[str, Any]]
    tags: Optional[List[str]]
    usage: Optional[UsageDict]
    model: Optional[str]
    provider: Optional[str]
    error_info: Optional[ErrorInfoDict]


@dataclasses.dataclass
class FeedbackScoreMessage(BaseMessage):
    """
    There is no handler for that in message processor, it exists
    only as an item of BatchMessage
    """

    id: str
    project_name: str
    name: str
    value: float
    source: str
    reason: Optional[str] = None
    category_name: Optional[str] = None


@dataclasses.dataclass
class AddTraceFeedbackScoresBatchMessage(BaseMessage):
    batch: List[FeedbackScoreMessage]


@dataclasses.dataclass
class AddSpanFeedbackScoresBatchMessage(BaseMessage):
    batch: List[FeedbackScoreMessage]


@dataclasses.dataclass
class CreateSpansBatchMessage(BaseMessage):
    batch: List[CreateSpanMessage]


@dataclasses.dataclass
class CreateTraceBatchMessage(BaseMessage):
    batch: List[CreateTraceMessage]

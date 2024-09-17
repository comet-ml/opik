import dataclasses
import datetime
from typing import Optional, Any, Dict, List
from ..types import UsageDict, SpanType


@dataclasses.dataclass
class BaseMessage:
    pass


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


@dataclasses.dataclass
class UpdateTraceMessage(BaseMessage):
    trace_id: str
    project_name: str
    end_time: Optional[datetime.datetime]
    input: Optional[Dict[str, Any]]
    output: Optional[Dict[str, Any]]
    metadata: Optional[Dict[str, Any]]
    tags: Optional[List[str]]


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


@dataclasses.dataclass
class UpdateSpanMessage(BaseMessage):
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

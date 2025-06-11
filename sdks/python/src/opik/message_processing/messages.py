import dataclasses
import datetime
from dataclasses import field
from typing import Optional, Any, Dict, List, Union, Literal

from . import arguments_utils
from ..rest_api.types import span_write, trace_write
from ..types import SpanType, ErrorInfoDict, LLMProvider, AttachmentEntityType


@dataclasses.dataclass
class BaseMessage:
    delivery_time: float = field(init=False, default=0.0)

    def as_payload_dict(self) -> Dict[str, Any]:
        # we are not using dataclasses.as_dict() here
        # because it will try to deepcopy all objects and will fail if there is a non-serializable object
        data = {**self.__dict__}
        if "delivery_time" in data:
            data.pop("delivery_time")
        return data


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
    thread_id: Optional[str]
    last_updated_at: Optional[datetime.datetime]

    def __post_init__(self) -> None:
        if self.input is not None:
            self.input = arguments_utils.recursive_shallow_copy(self.input)
        if self.output is not None:
            self.output = arguments_utils.recursive_shallow_copy(self.output)

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
    thread_id: Optional[str]

    def __post_init__(self) -> None:
        if self.input is not None:
            self.input = arguments_utils.recursive_shallow_copy(self.input)
        if self.output is not None:
            self.output = arguments_utils.recursive_shallow_copy(self.output)

    def as_payload_dict(self) -> Dict[str, Any]:
        data = super().as_payload_dict()
        data["id"] = data.pop("trace_id")
        return data


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
    usage: Optional[Dict[str, int]]
    model: Optional[str]
    provider: Optional[Union[LLMProvider, str]]
    error_info: Optional[ErrorInfoDict]
    total_cost: Optional[float]
    last_updated_at: Optional[datetime.datetime]

    def __post_init__(self) -> None:
        if self.input is not None:
            self.input = arguments_utils.recursive_shallow_copy(self.input)
        if self.output is not None:
            self.output = arguments_utils.recursive_shallow_copy(self.output)

    def as_payload_dict(self) -> Dict[str, Any]:
        data = super().as_payload_dict()
        data["id"] = data.pop("span_id")
        data["total_estimated_cost"] = data.pop("total_cost")
        return data


@dataclasses.dataclass
class UpdateSpanMessage(BaseMessage):
    """Not recommended to use. Kept only for low level update operations in public API"""

    span_id: str
    parent_span_id: Optional[str]
    trace_id: str
    project_name: str
    end_time: Optional[datetime.datetime]
    input: Optional[Dict[str, Any]]
    output: Optional[Dict[str, Any]]
    metadata: Optional[Dict[str, Any]]
    tags: Optional[List[str]]
    usage: Optional[Dict[str, int]]
    model: Optional[str]
    provider: Optional[Union[LLMProvider, str]]
    error_info: Optional[ErrorInfoDict]
    total_cost: Optional[float]

    def __post_init__(self) -> None:
        if self.input is not None:
            self.input = arguments_utils.recursive_shallow_copy(self.input)
        if self.output is not None:
            self.output = arguments_utils.recursive_shallow_copy(self.output)

    def as_payload_dict(self) -> Dict[str, Any]:
        data = super().as_payload_dict()
        data["id"] = data.pop("span_id")
        data["total_estimated_cost"] = data.pop("total_cost")
        return data


@dataclasses.dataclass
class FeedbackScoreMessage(BaseMessage):
    """
    There is no handler for that in the message processor, it exists
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
class AddFeedbackScoresBatchMessage(BaseMessage):
    batch: List[FeedbackScoreMessage]
    supports_batching: bool = True

    def as_payload_dict(self) -> Dict[str, Any]:
        data = super().as_payload_dict()
        data.pop("supports_batching")
        return data


@dataclasses.dataclass
class AddTraceFeedbackScoresBatchMessage(AddFeedbackScoresBatchMessage):
    pass


@dataclasses.dataclass
class AddSpanFeedbackScoresBatchMessage(AddFeedbackScoresBatchMessage):
    pass


@dataclasses.dataclass
class CreateSpansBatchMessage(BaseMessage):
    batch: List[span_write.SpanWrite]


@dataclasses.dataclass
class CreateTraceBatchMessage(BaseMessage):
    batch: List[trace_write.TraceWrite]


@dataclasses.dataclass
class GuardrailBatchItemMessage(BaseMessage):
    """
    There is no handler for that in the message processor, it exists
    only as an item of BatchMessage
    """

    project_name: Optional[str]
    entity_id: str
    secondary_id: str
    name: str
    result: Union[Literal["passed", "failed"], Any]
    config: Dict[str, Any]
    details: Dict[str, Any]


@dataclasses.dataclass
class GuardrailBatchMessage(BaseMessage):
    batch: List[GuardrailBatchItemMessage]
    supports_batching: bool = True

    def as_payload_dict(self) -> Dict[str, Any]:
        data = super().as_payload_dict()
        data.pop("supports_batching")
        return data


@dataclasses.dataclass
class CreateAttachmentMessage(BaseMessage):
    file_path: str
    file_name: str
    mime_type: Optional[str]
    entity_type: AttachmentEntityType
    entity_id: str
    project_name: str
    encoded_url_override: str

from __future__ import annotations
import dataclasses
import datetime
from dataclasses import field
from typing import Optional, Any, Dict, List, Union, Literal, Set, Type, TypeVar

from . import arguments_utils
from .preprocessing import constants
from ..rest_api.types import span_write, trace_write
from ..types import SpanType, ErrorInfoDict, LLMProvider, AttachmentEntityType


T = TypeVar("T", bound="BaseMessage")


def from_db_message_dict(message_class: Type[T], data: Dict[str, Any]) -> T:
    return message_class(**data)


@dataclasses.dataclass
class BaseMessage:
    delivery_time: float = field(init=False, default=0.0)
    delivery_attempts: int = field(init=False, default=1)

    message_id: Optional[int] = field(init=False, default=None)
    message_type: str = field(init=False, default="BaseMessage")

    def as_payload_dict(self) -> Dict[str, Any]:
        # we are not using dataclasses.as_dict() here
        # because it will try to deepcopy all objects and will fail if there is a non-serializable object
        data = {**self.__dict__}
        attributes_to_remove = [
            "delivery_time",
            "delivery_attempts",
            constants.MARKER_ATTRIBUTE_NAME,
            "message_id",
            "message_type",
        ]
        for attribute in attributes_to_remove:
            data.pop(attribute, None)
        return data

    def as_db_message_dict(self) -> Dict[str, Any]:
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
    thread_id: Optional[str]
    last_updated_at: Optional[datetime.datetime]

    message_type = "CreateTraceMessage"

    def __post_init__(self) -> None:
        if self.input is not None:
            self.input = arguments_utils.recursive_shallow_copy(self.input)
        if self.output is not None:
            self.output = arguments_utils.recursive_shallow_copy(self.output)

    def as_payload_dict(self) -> Dict[str, Any]:
        data = super().as_payload_dict()
        data["id"] = data.pop("trace_id")
        return data

    @staticmethod
    def fields_to_anonymize() -> Set[str]:
        return {"input", "output", "metadata"}


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

    message_type = "UpdateTraceMessage"

    def __post_init__(self) -> None:
        if self.input is not None:
            self.input = arguments_utils.recursive_shallow_copy(self.input)
        if self.output is not None:
            self.output = arguments_utils.recursive_shallow_copy(self.output)

    def as_payload_dict(self) -> Dict[str, Any]:
        data = super().as_payload_dict()
        data["id"] = data.pop("trace_id")
        return data

    @staticmethod
    def fields_to_anonymize() -> Set[str]:
        return {"input", "output", "metadata"}


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

    message_type = "CreateSpanMessage"

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

    @staticmethod
    def fields_to_anonymize() -> Set[str]:
        return {"input", "output", "metadata"}


@dataclasses.dataclass
class UpdateSpanMessage(BaseMessage):
    """Not recommended to use. Kept only for low-level update operations in public API"""

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

    message_type = "UpdateSpanMessage"

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

    @staticmethod
    def fields_to_anonymize() -> Set[str]:
        return {"input", "output", "metadata"}


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

    message_type = "FeedbackScoreMessage"


@dataclasses.dataclass
class AddFeedbackScoresBatchMessage(BaseMessage):
    batch: List[FeedbackScoreMessage]
    supports_batching: bool = True

    message_type = "AddFeedbackScoresBatchMessage"


@dataclasses.dataclass
class AddTraceFeedbackScoresBatchMessage(AddFeedbackScoresBatchMessage):
    message_type = "AddTraceFeedbackScoresBatchMessage"


@dataclasses.dataclass
class AddSpanFeedbackScoresBatchMessage(AddFeedbackScoresBatchMessage):
    message_type = "AddSpanFeedbackScoresBatchMessage"


@dataclasses.dataclass
class ThreadsFeedbackScoreMessage(FeedbackScoreMessage):
    """
    There is no handler for that in the message processor, it exists
    only as an item of AddThreadsFeedbackScoresBatchMessage
    """

    def as_payload_dict(self) -> Dict[str, Any]:
        data = super().as_payload_dict()
        data["thread_id"] = data.pop("id")
        return data


@dataclasses.dataclass
class AddThreadsFeedbackScoresBatchMessage(BaseMessage):
    batch: List[ThreadsFeedbackScoreMessage]
    supports_batching: bool = True

    message_type = "AddThreadsFeedbackScoresBatchMessage"


@dataclasses.dataclass
class CreateSpansBatchMessage(BaseMessage):
    batch: List[span_write.SpanWrite]

    message_type = "CreateSpansBatchMessage"

    @staticmethod
    def fields_to_anonymize() -> Set[str]:
        return {"input", "output", "metadata"}


@dataclasses.dataclass
class CreateTraceBatchMessage(BaseMessage):
    batch: List[trace_write.TraceWrite]

    message_type = "CreateTraceBatchMessage"

    @staticmethod
    def fields_to_anonymize() -> Set[str]:
        return {"input", "output", "metadata"}


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

    message_type = "GuardrailBatchItemMessage"


@dataclasses.dataclass
class GuardrailBatchMessage(BaseMessage):
    batch: List[GuardrailBatchItemMessage]
    supports_batching: bool = True

    message_type = "GuardrailBatchMessage"

    def as_payload_dict(self) -> Dict[str, Any]:
        data = super().as_payload_dict()
        data.pop("supports_batching")
        return data


@dataclasses.dataclass
class ExperimentItemMessage(BaseMessage):
    """
    There is no handler for that in the message processor, it exists
    only as an item of CreateExperimentItemsBatchMessage
    """

    id: str
    experiment_id: str
    trace_id: str
    dataset_item_id: str

    message_type = "ExperimentItemMessage"


@dataclasses.dataclass
class CreateExperimentItemsBatchMessage(BaseMessage):
    batch: List[ExperimentItemMessage]
    supports_batching: bool = True

    message_type = "CreateExperimentItemsBatchMessage"


@dataclasses.dataclass
class CreateAttachmentMessage(BaseMessage):
    file_path: str
    file_name: str
    mime_type: Optional[str]
    entity_type: AttachmentEntityType
    entity_id: str
    project_name: str
    encoded_url_override: str
    delete_after_upload: bool = False

    message_type = "CreateAttachmentMessage"


@dataclasses.dataclass
class AttachmentSupportingMessage(BaseMessage):
    original_message: BaseMessage

    message_type = "AttachmentSupportingMessage"

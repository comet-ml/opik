import dataclasses
import datetime
from dataclasses import field
from typing import Any, Literal

from . import arguments_utils
from .preprocessing import constants
from ..rest_api.types import span_write, trace_write
from ..types import SpanType, ErrorInfoDict, LLMProvider, AttachmentEntityType


@dataclasses.dataclass
class BaseMessage:
    delivery_time: float = field(init=False, default=0.0)
    delivery_attempts: int = field(init=False, default=1)

    def as_payload_dict(self) -> dict[str, Any]:
        # we are not using dataclasses.as_dict() here
        # because it will try to deepcopy all objects and will fail if there is a non-serializable object
        data = {**self.__dict__}
        if "delivery_time" in data:
            data.pop("delivery_time")
        if "delivery_attempts" in data:
            data.pop("delivery_attempts")
        if constants.MARKER_ATTRIBUTE_NAME in data:
            data.pop(constants.MARKER_ATTRIBUTE_NAME)
        return data


@dataclasses.dataclass
class CreateTraceMessage(BaseMessage):
    trace_id: str
    project_name: str
    name: str | None
    start_time: datetime.datetime
    end_time: datetime.datetime | None
    input: dict[str, Any] | None
    output: dict[str, Any] | None
    metadata: dict[str, Any] | None
    tags: list[str] | None
    error_info: ErrorInfoDict | None
    thread_id: str | None
    last_updated_at: datetime.datetime | None

    def __post_init__(self) -> None:
        if self.input is not None:
            self.input = arguments_utils.recursive_shallow_copy(self.input)
        if self.output is not None:
            self.output = arguments_utils.recursive_shallow_copy(self.output)

    def as_payload_dict(self) -> dict[str, Any]:
        data = super().as_payload_dict()
        data["id"] = data.pop("trace_id")
        return data

    @staticmethod
    def fields_to_anonymize() -> set[str]:
        return {"input", "output", "metadata"}


@dataclasses.dataclass
class UpdateTraceMessage(BaseMessage):
    """
    "Not recommended to use. Kept only for low level update operations in public API"
    """

    trace_id: str
    project_name: str
    end_time: datetime.datetime | None
    input: dict[str, Any] | None
    output: dict[str, Any] | None
    metadata: dict[str, Any] | None
    tags: list[str] | None
    error_info: ErrorInfoDict | None
    thread_id: str | None

    def __post_init__(self) -> None:
        if self.input is not None:
            self.input = arguments_utils.recursive_shallow_copy(self.input)
        if self.output is not None:
            self.output = arguments_utils.recursive_shallow_copy(self.output)

    def as_payload_dict(self) -> dict[str, Any]:
        data = super().as_payload_dict()
        data["id"] = data.pop("trace_id")
        return data

    @staticmethod
    def fields_to_anonymize() -> set[str]:
        return {"input", "output", "metadata"}


@dataclasses.dataclass
class CreateSpanMessage(BaseMessage):
    span_id: str
    trace_id: str
    project_name: str
    parent_span_id: str | None
    name: str | None
    start_time: datetime.datetime
    end_time: datetime.datetime | None
    input: dict[str, Any] | None
    output: dict[str, Any] | None
    metadata: dict[str, Any] | None
    tags: list[str] | None
    type: SpanType
    usage: dict[str, int] | None
    model: str | None
    provider: LLMProvider | str | None
    error_info: ErrorInfoDict | None
    total_cost: float | None
    last_updated_at: datetime.datetime | None

    def __post_init__(self) -> None:
        if self.input is not None:
            self.input = arguments_utils.recursive_shallow_copy(self.input)
        if self.output is not None:
            self.output = arguments_utils.recursive_shallow_copy(self.output)

    def as_payload_dict(self) -> dict[str, Any]:
        data = super().as_payload_dict()
        data["id"] = data.pop("span_id")
        data["total_estimated_cost"] = data.pop("total_cost")
        return data

    @staticmethod
    def fields_to_anonymize() -> set[str]:
        return {"input", "output", "metadata"}


@dataclasses.dataclass
class UpdateSpanMessage(BaseMessage):
    """Not recommended to use. Kept only for low level update operations in public API"""

    span_id: str
    parent_span_id: str | None
    trace_id: str
    project_name: str
    end_time: datetime.datetime | None
    input: dict[str, Any] | None
    output: dict[str, Any] | None
    metadata: dict[str, Any] | None
    tags: list[str] | None
    usage: dict[str, int] | None
    model: str | None
    provider: LLMProvider | str | None
    error_info: ErrorInfoDict | None
    total_cost: float | None

    def __post_init__(self) -> None:
        if self.input is not None:
            self.input = arguments_utils.recursive_shallow_copy(self.input)
        if self.output is not None:
            self.output = arguments_utils.recursive_shallow_copy(self.output)

    def as_payload_dict(self) -> dict[str, Any]:
        data = super().as_payload_dict()
        data["id"] = data.pop("span_id")
        data["total_estimated_cost"] = data.pop("total_cost")
        return data

    @staticmethod
    def fields_to_anonymize() -> set[str]:
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
    reason: str | None = None
    category_name: str | None = None


@dataclasses.dataclass
class AddFeedbackScoresBatchMessage(BaseMessage):
    batch: list[FeedbackScoreMessage]
    supports_batching: bool = True


@dataclasses.dataclass
class AddTraceFeedbackScoresBatchMessage(AddFeedbackScoresBatchMessage):
    pass


@dataclasses.dataclass
class AddSpanFeedbackScoresBatchMessage(AddFeedbackScoresBatchMessage):
    pass


@dataclasses.dataclass
class ThreadsFeedbackScoreMessage(FeedbackScoreMessage):
    """
    There is no handler for that in the message processor, it exists
    only as an item of AddThreadsFeedbackScoresBatchMessage
    """

    def as_payload_dict(self) -> dict[str, Any]:
        data = super().as_payload_dict()
        data["thread_id"] = data.pop("id")
        return data


@dataclasses.dataclass
class AddThreadsFeedbackScoresBatchMessage(BaseMessage):
    batch: list[ThreadsFeedbackScoreMessage]
    supports_batching: bool = True


@dataclasses.dataclass
class CreateSpansBatchMessage(BaseMessage):
    batch: list[span_write.SpanWrite]

    @staticmethod
    def fields_to_anonymize() -> set[str]:
        return {"input", "output", "metadata"}


@dataclasses.dataclass
class CreateTraceBatchMessage(BaseMessage):
    batch: list[trace_write.TraceWrite]

    @staticmethod
    def fields_to_anonymize() -> set[str]:
        return {"input", "output", "metadata"}


@dataclasses.dataclass
class GuardrailBatchItemMessage(BaseMessage):
    """
    There is no handler for that in the message processor, it exists
    only as an item of BatchMessage
    """

    project_name: str | None
    entity_id: str
    secondary_id: str
    name: str
    result: Literal["passed", "failed"] | Any
    config: dict[str, Any]
    details: dict[str, Any]


@dataclasses.dataclass
class GuardrailBatchMessage(BaseMessage):
    batch: list[GuardrailBatchItemMessage]
    supports_batching: bool = True

    def as_payload_dict(self) -> dict[str, Any]:
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


@dataclasses.dataclass
class CreateExperimentItemsBatchMessage(BaseMessage):
    batch: list[ExperimentItemMessage]
    supports_batching: bool = True


@dataclasses.dataclass
class CreateAttachmentMessage(BaseMessage):
    file_path: str
    file_name: str
    mime_type: str | None
    entity_type: AttachmentEntityType
    entity_id: str
    project_name: str
    encoded_url_override: str
    delete_after_upload: bool = False


@dataclasses.dataclass
class AttachmentSupportingMessage(BaseMessage):
    original_message: BaseMessage

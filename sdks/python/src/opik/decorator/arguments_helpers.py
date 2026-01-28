import dataclasses
from typing import Any
from collections.abc import Callable

from .. import datetime_helpers, llm_usage
from ..api_objects import helpers, span, attachment
from ..types import ErrorInfoDict, SpanType, DistributedTraceHeadersDict


@dataclasses.dataclass
class BaseArguments:
    def to_kwargs(self, ignore_keys: list[str] | None = None) -> dict[str, Any]:
        result: dict[str, Any] = {}
        ignore_keys = [] if ignore_keys is None else ignore_keys
        for key, value in self.__dict__.items():
            if (value is not None) and (key not in ignore_keys):
                result[key] = value

        return result


@dataclasses.dataclass
class EndSpanParameters(BaseArguments):
    """
    Span data parameters that we set (or might set) when the
    tracked function is ended.
    """

    name: str | None = None
    metadata: Any | None = None
    input: dict[str, Any] | None = None
    output: dict[str, Any] | None = None
    tags: list[str] | None = None
    usage: dict[str, Any] | llm_usage.OpikUsage | None = None
    model: str | None = None
    provider: str | None = None
    error_info: ErrorInfoDict | None = None
    total_cost: float | None = None
    attachments: list[attachment.Attachment] | None = None


@dataclasses.dataclass
class StartSpanParameters(BaseArguments):
    """
    Span data parameters that we set (or might set) when the
    tracked function is started.
    """

    type: SpanType
    name: str
    tags: list[str] | None = None
    metadata: dict[str, Any] | None = None
    input: dict[str, Any] | None = None
    project_name: str | None = None
    model: str | None = None
    provider: str | None = None
    thread_id: str | None = None  # used for traces only


@dataclasses.dataclass
class TrackOptions(BaseArguments):
    """
    A storage for all arguments passed to the `track` decorator.
    """

    name: str | None
    type: SpanType
    tags: list[str] | None
    metadata: dict[str, Any] | None
    capture_input: bool
    ignore_arguments: list[str] | None
    capture_output: bool
    generations_aggregator: Callable[[list[Any]], Any] | None
    flush: bool
    project_name: str | None
    create_duplicate_root_span: bool


def create_span_data(
    start_span_arguments: StartSpanParameters,
    trace_id: str,
    parent_span_id: str | None = None,
) -> span.SpanData:
    span_data = span.SpanData(
        id=helpers.generate_id(),
        parent_span_id=parent_span_id,
        trace_id=trace_id,
        start_time=datetime_helpers.local_timestamp(),
        name=start_span_arguments.name,
        type=start_span_arguments.type,
        tags=start_span_arguments.tags,
        metadata=start_span_arguments.metadata,
        input=start_span_arguments.input,
        project_name=start_span_arguments.project_name,
        model=start_span_arguments.model,
        provider=start_span_arguments.provider,
    )
    return span_data


def extract_distributed_trace_headers(
    kwargs: dict[str, Any],
) -> DistributedTraceHeadersDict | None:
    return kwargs.pop("opik_distributed_trace_headers", None)

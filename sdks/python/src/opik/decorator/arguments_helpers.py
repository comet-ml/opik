import dataclasses
from typing import Any, Callable, Dict, List, Optional, Union

from .. import datetime_helpers, llm_usage
from ..api_objects import helpers, span
from ..types import ErrorInfoDict, SpanType


@dataclasses.dataclass
class BaseArguments:
    def to_kwargs(self, ignore_keys: Optional[List[str]] = None) -> Dict[str, Any]:
        result: Dict[str, Any] = {}
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

    name: Optional[str] = None
    metadata: Optional[Any] = None
    input: Optional[Dict[str, Any]] = None
    output: Optional[Dict[str, Any]] = None
    tags: Optional[List[str]] = None
    usage: Optional[Union[Dict[str, Any], llm_usage.OpikUsage]] = None
    model: Optional[str] = None
    provider: Optional[str] = None
    error_info: Optional[ErrorInfoDict] = None


@dataclasses.dataclass
class StartSpanParameters(BaseArguments):
    """
    Span data parameters that we set (or might set) when the
    tracked function is started.
    """

    type: SpanType
    name: str
    tags: Optional[List[str]] = None
    metadata: Optional[Dict[str, Any]] = None
    input: Optional[Dict[str, Any]] = None
    project_name: Optional[str] = None
    model: Optional[str] = None
    provider: Optional[str] = None


@dataclasses.dataclass
class TrackOptions(BaseArguments):
    """
    A storage for all arguments passed to the `track` decorator.
    """

    name: Optional[str]
    type: SpanType
    tags: Optional[List[str]]
    metadata: Optional[Dict[str, Any]]
    capture_input: bool
    ignore_arguments: Optional[List[str]]
    capture_output: bool
    generations_aggregator: Optional[Callable[[List[Any]], Any]]
    flush: bool
    project_name: Optional[str]


def create_span_data(
    start_span_arguments: StartSpanParameters,
    trace_id: str,
    parent_span_id: Optional[str] = None,
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

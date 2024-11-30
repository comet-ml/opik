from typing import Optional, Any, Dict, List, Callable
from ..types import SpanType

import dataclasses


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

    metadata: Optional[Any] = None
    input: Optional[Dict[str, Any]] = None
    output: Optional[Dict[str, Any]] = None
    tags: Optional[List[str]] = None
    usage: Optional[Dict[str, Any]] = None
    model: Optional[str] = None
    provider: Optional[str] = None


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

from typing import Optional, Any, Dict, List
from ..types import SpanType

import dataclasses


@dataclasses.dataclass
class BaseArguments:
    def to_kwargs(self) -> Dict[str, Any]:
        result: Dict[str, Any] = {}

        for key, value in self.__dict__.items():
            if value is not None:
                result[key] = value

        return result


@dataclasses.dataclass
class EndSpanArguments(BaseArguments):
    metadata: Optional[Any] = None
    input: Optional[Dict[str, Any]] = None
    output: Optional[Dict[str, Any]] = None
    tags: Optional[List[str]] = None
    usage: Optional[Dict[str, Any]] = None


@dataclasses.dataclass
class StartSpanArguments(BaseArguments):
    type: SpanType
    name: Optional[str] = None
    tags: Optional[List[str]] = None
    metadata: Optional[Dict[str, Any]] = None
    input: Optional[Dict[str, Any]] = None

from typing import Any, Dict, TypedDict

from botocore import eventstream


class ConverseStreamOutput(TypedDict):
    stream: eventstream.EventStream
    ResponseMetadata: Dict[str, Any]


class InvokeModelStreamOutput(TypedDict):
    body: eventstream.EventStream
    ResponseMetadata: Dict[str, Any]

from typing import Any, TypedDict

from botocore import eventstream
import botocore.response


class ConverseStreamOutput(TypedDict):
    stream: eventstream.EventStream
    ResponseMetadata: dict[str, Any]


class InvokeModelOutput(TypedDict):
    body: botocore.response.StreamingBody
    ResponseMetadata: dict[str, Any]


class InvokeModelWithResponseStreamOutput(TypedDict):
    body: eventstream.EventStream
    ResponseMetadata: dict[str, Any]

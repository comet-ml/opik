from typing import Any, Dict, TypedDict

from botocore import eventstream
import botocore.response


class ConverseStreamOutput(TypedDict):
    stream: eventstream.EventStream
    ResponseMetadata: Dict[str, Any]


class InvokeModelOutput(TypedDict):
    body: botocore.response.StreamingBody
    ResponseMetadata: Dict[str, Any]


class InvokeModelWithResponseStreamOutput(TypedDict):
    body: eventstream.EventStream
    ResponseMetadata: Dict[str, Any]

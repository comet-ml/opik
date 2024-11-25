# This file was auto-generated by Fern from our API Definition.

from ..core.pydantic_utilities import UniversalBaseModel
import typing
import pydantic
from .span_write_type import SpanWriteType
import datetime as dt
from .json_node_write import JsonNodeWrite
from ..core.pydantic_utilities import IS_PYDANTIC_V2


class SpanWrite(UniversalBaseModel):
    id: typing.Optional[str] = None
    project_name: typing.Optional[str] = pydantic.Field(default=None)
    """
    If null, the default project is used
    """

    trace_id: str
    parent_span_id: typing.Optional[str] = None
    name: str
    type: SpanWriteType
    start_time: dt.datetime
    end_time: typing.Optional[dt.datetime] = None
    input: typing.Optional[JsonNodeWrite] = None
    output: typing.Optional[JsonNodeWrite] = None
    metadata: typing.Optional[JsonNodeWrite] = None
    model: typing.Optional[str] = None
    provider: typing.Optional[str] = None
    tags: typing.Optional[typing.List[str]] = None
    usage: typing.Optional[typing.Dict[str, int]] = None

    if IS_PYDANTIC_V2:
        model_config: typing.ClassVar[pydantic.ConfigDict] = pydantic.ConfigDict(
            extra="allow", frozen=True
        )  # type: ignore # Pydantic v2
    else:

        class Config:
            frozen = True
            smart_union = True
            extra = pydantic.Extra.allow
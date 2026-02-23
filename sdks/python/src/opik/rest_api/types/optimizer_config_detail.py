import datetime as dt
import typing

import pydantic
from ..core.pydantic_utilities import IS_PYDANTIC_V2, UniversalBaseModel


class OptimizerConfigParameter(UniversalBaseModel):
    key: str
    type: str
    value: typing.Optional[typing.Any] = None

    if IS_PYDANTIC_V2:
        model_config: typing.ClassVar[pydantic.ConfigDict] = pydantic.ConfigDict(extra="allow", frozen=True)  # type: ignore
    else:

        class Config:
            frozen = True
            smart_union = True
            extra = pydantic.Extra.allow


class OptimizerConfigBlueprint(UniversalBaseModel):
    id: typing.Optional[str] = None
    description: typing.Optional[str] = None
    created_by: typing.Optional[str] = None
    created_at: typing.Optional[dt.datetime] = None
    values: typing.Optional[typing.List[OptimizerConfigParameter]] = None

    if IS_PYDANTIC_V2:
        model_config: typing.ClassVar[pydantic.ConfigDict] = pydantic.ConfigDict(extra="allow", frozen=True)  # type: ignore
    else:

        class Config:
            frozen = True
            smart_union = True
            extra = pydantic.Extra.allow


class OptimizerConfigCreateResponse(UniversalBaseModel):
    id: typing.Optional[str] = None

    if IS_PYDANTIC_V2:
        model_config: typing.ClassVar[pydantic.ConfigDict] = pydantic.ConfigDict(extra="allow", frozen=True)  # type: ignore
    else:

        class Config:
            frozen = True
            smart_union = True
            extra = pydantic.Extra.allow

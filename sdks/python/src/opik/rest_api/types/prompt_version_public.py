# This file was auto-generated by Fern from our API Definition.

from ..core.pydantic_utilities import UniversalBaseModel
import typing
import pydantic
import datetime as dt
from ..core.pydantic_utilities import IS_PYDANTIC_V2


class PromptVersionPublic(UniversalBaseModel):
    id: typing.Optional[str] = pydantic.Field(default=None)
    """
    version unique identifier, generated if absent
    """

    prompt_id: typing.Optional[str] = None
    commit: typing.Optional[str] = pydantic.Field(default=None)
    """
    version short unique identifier, generated if absent. it must be 8 characters long
    """

    template: str
    created_at: typing.Optional[dt.datetime] = None
    created_by: typing.Optional[str] = None

    if IS_PYDANTIC_V2:
        model_config: typing.ClassVar[pydantic.ConfigDict] = pydantic.ConfigDict(
            extra="allow", frozen=True
        )  # type: ignore # Pydantic v2
    else:

        class Config:
            frozen = True
            smart_union = True
            extra = pydantic.Extra.allow
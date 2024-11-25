# This file was auto-generated by Fern from our API Definition.

from ..core.pydantic_utilities import UniversalBaseModel
import typing
import pydantic
from .dataset_item import DatasetItem
from ..core.pydantic_utilities import IS_PYDANTIC_V2


class DatasetItemBatch(UniversalBaseModel):
    dataset_name: typing.Optional[str] = pydantic.Field(default=None)
    """
    If null, dataset_id must be provided
    """

    dataset_id: typing.Optional[str] = pydantic.Field(default=None)
    """
    If null, dataset_name must be provided
    """

    items: typing.List[DatasetItem]

    if IS_PYDANTIC_V2:
        model_config: typing.ClassVar[pydantic.ConfigDict] = pydantic.ConfigDict(
            extra="allow", frozen=True
        )  # type: ignore # Pydantic v2
    else:

        class Config:
            frozen = True
            smart_union = True
            extra = pydantic.Extra.allow
from typing import Any, Literal, Union
from collections.abc import Callable

import pydantic


class PropertySchema(pydantic.BaseModel):
    """JSON Schema for a single property in tool parameters."""

    model_config = pydantic.ConfigDict(extra="allow")

    description: str | None = None
    type: Any | None = None
    properties: dict[str, "PropertySchema"] | None = None


class ToolParameters(pydantic.BaseModel):
    """JSON Schema for tool/function parameters (OpenAI function calling format)."""

    type: Literal["object"] | None = None
    properties: dict[str, PropertySchema] | None = None
    required: list[str] | None = None
    additionalProperties: bool | dict[str, Any] | None = None


class FunctionTool(pydantic.BaseModel):
    name: str
    description: str
    parameters: ToolParameters


class Tool(pydantic.BaseModel):
    type: Literal["function"]
    function: FunctionTool


class ImageURL(pydantic.BaseModel):
    """Image URL content part for OpenAI messages."""

    url: str
    detail: Literal["low", "high", "auto"] | None = None


class TextContentPart(pydantic.BaseModel):
    """Text content part for OpenAI messages."""

    type: Literal["text"]
    text: str


class ImageContentPart(pydantic.BaseModel):
    """Image content part for OpenAI messages."""

    type: Literal["image_url"]
    image_url: ImageURL


ContentPart = Union[TextContentPart, ImageContentPart]
Content = Union[str, list[ContentPart]]


class Message(pydantic.BaseModel):
    role: Literal["user", "assistant", "system"]
    content: Content


class DatasetSplitPreset(pydantic.BaseModel):
    """Configuration for a named dataset split slice."""

    model_config = pydantic.ConfigDict(arbitrary_types_allowed=True)

    source_split: str
    start: int | None = None
    count: int | None = None
    dataset_name: str | None = None


class DatasetSpec(pydantic.BaseModel):
    """Declarative description of an optimizer dataset."""

    model_config = pydantic.ConfigDict(arbitrary_types_allowed=True)

    name: str
    default_source_split: str = "train"
    hf_path: str | None = None
    hf_name: str | None = None
    load_kwargs_resolver: Callable[[str], dict[str, Any]] | None = None
    presets: dict[str, DatasetSplitPreset] = pydantic.Field(default_factory=dict)
    prefer_presets: bool = False
    custom_loader: (
        Callable[[str, int, int | None, int], list[dict[str, Any]]] | None
    ) = None
    records_transform: Callable[[list[dict[str, Any]]], list[dict[str, Any]]] | None = (
        None
    )

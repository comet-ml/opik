from typing import Any, Literal, Union

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

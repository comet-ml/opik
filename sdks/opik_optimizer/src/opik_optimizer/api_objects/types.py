from typing import Any, Literal, Protocol, Union
from collections.abc import Callable

import pydantic
from opik.evaluation.metrics import score_result


class MetricFunction(Protocol):
    """Protocol for metric functions used in optimization.

    Metric functions take a dataset item and LLM output, returning a score.
    All Python functions have __name__ by default.

    Example:
        def my_metric(dataset_item: dict[str, Any], llm_output: str) -> float:
            return 1.0 if llm_output == dataset_item["expected"] else 0.0
    """

    __name__: str

    def __call__(
        self,
        dataset_item: dict[str, Any],
        llm_output: str,
        **kwargs: Any,
    ) -> float | score_result.ScoreResult | list[score_result.ScoreResult]: ...


class PropertySchema(pydantic.BaseModel):
    """JSON Schema for a single property in tool parameters."""

    model_config = pydantic.ConfigDict(extra="allow")

    description: str | None = None
    type: Any | None = None
    properties: dict[str, "PropertySchema"] | None = None


class ToolParameters(pydantic.BaseModel):
    """JSON Schema for tool/function parameters (OpenAI function calling format)."""

    model_config = pydantic.ConfigDict(extra="forbid")

    type: Literal["object"] | None = None
    properties: dict[str, PropertySchema] | None = None
    required: list[str] | None = None
    additionalProperties: bool | dict[str, Any] | None = None


class FunctionTool(pydantic.BaseModel):
    model_config = pydantic.ConfigDict(extra="forbid")

    name: str
    description: str
    parameters: ToolParameters


class Tool(pydantic.BaseModel):
    model_config = pydantic.ConfigDict(extra="forbid")

    type: Literal["function"]
    function: FunctionTool


class ImageURL(pydantic.BaseModel):
    """Image URL content part for OpenAI messages."""

    model_config = pydantic.ConfigDict(extra="forbid")

    url: str
    detail: Literal["low", "high", "auto"] | None = None


class TextContentPart(pydantic.BaseModel):
    """Text content part for OpenAI messages."""

    model_config = pydantic.ConfigDict(extra="forbid")

    type: Literal["text"]
    text: str


class ImageContentPart(pydantic.BaseModel):
    """Image content part for OpenAI messages."""

    model_config = pydantic.ConfigDict(extra="forbid")

    type: Literal["image_url"]
    image_url: ImageURL


ContentPart = Union[TextContentPart, ImageContentPart]
Content = Union[str, list[ContentPart]]


def extract_text_from_content(content: Content) -> str:
    """Extract text from Content (str or list of ContentParts).

    Assumes at most one text part per message.

    Args:
        content: Message content, either a string or a list of content parts.

    Returns:
        The text content as a string.
    """
    if isinstance(content, str):
        return content
    for part in content:
        if isinstance(part, dict) and part.get("type") == "text":
            return part.get("text", "")
    return ""


def rebuild_content_with_new_text(original_content: Content, new_text: str) -> Content:
    """Replace text part with new_text, preserving non-text parts (images/video).

    Assumes at most one text part per message.

    Args:
        original_content: The original content (string or list of parts).
        new_text: The new text to replace the text part with.

    Returns:
        Updated content with the same structure as the original.
    """
    import copy

    if isinstance(original_content, str):
        return new_text
    result: list[dict[str, Any]] = []
    for part in original_content:
        if isinstance(part, dict) and part.get("type") == "text":
            result.append({"type": "text", "text": new_text})
        else:
            result.append(copy.deepcopy(part))  # type: ignore[arg-type]
    return result  # type: ignore[return-value]


class Message(pydantic.BaseModel):
    model_config = pydantic.ConfigDict(extra="forbid")

    role: Literal["user", "assistant", "system"]
    content: Content


# Type alias for a list of messages
Messages = list[Message]


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

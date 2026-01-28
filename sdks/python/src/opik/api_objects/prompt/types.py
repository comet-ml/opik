from __future__ import annotations

from typing import Any, Literal, Optional, Union
from collections.abc import Callable, Mapping

import enum


class PromptType(str, enum.Enum):
    MUSTACHE = "mustache"
    JINJA2 = "jinja2"


# Core multimodal/chat prompt related types
MessageContent = Union[str, list[dict[str, Any]]]
ContentPart = dict[str, Any]
RendererFn = Callable[[ContentPart, dict[str, Any], PromptType], Optional[ContentPart]]
ModalityName = Literal["vision", "video"]
SupportedModalities = Mapping[ModalityName, bool]
ModalitySet = set[ModalityName]

__all__ = [
    "PromptType",
    "MessageContent",
    "ContentPart",
    "RendererFn",
    "ModalityName",
    "SupportedModalities",
    "ModalitySet",
]

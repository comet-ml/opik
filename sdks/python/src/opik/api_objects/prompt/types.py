from __future__ import annotations

from typing import Any, Callable, Dict, List, Literal, Mapping, Optional, Union, Set

import enum


class PromptType(str, enum.Enum):
    MUSTACHE = "mustache"
    JINJA2 = "jinja2"


# Core multimodal/chat prompt related types
MessageContent = Union[str, List[Dict[str, Any]]]
ContentPart = Dict[str, Any]
RendererFn = Callable[[ContentPart, Dict[str, Any], PromptType], Optional[ContentPart]]
ModalityName = Literal["vision", "video"]
SupportedModalities = Mapping[ModalityName, bool]
ModalitySet = Set[ModalityName]

__all__ = [
    "PromptType",
    "MessageContent",
    "ContentPart",
    "RendererFn",
    "ModalityName",
    "SupportedModalities",
    "ModalitySet",
]

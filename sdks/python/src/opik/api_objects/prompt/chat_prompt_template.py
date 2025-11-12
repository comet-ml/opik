"""
Tools for rendering chat-style prompts with multimodal content.

The template mirrors :class:`PromptTemplate` but works on a list of OpenAI-like
messages. Rendering is handled by a registry of part renderers so additional
modalities can be plugged in without changing the core implementation.
"""

from __future__ import annotations

from typing import Any, Dict, List, Optional, Union, cast

from .prompt_template import PromptTemplate
from .types import (
    PromptType,
    MessageContent,
    ContentPart,
    SupportedModalities,
    ModalitySet,
)
from .chat_content_renderer_registry import (
    ChatContentRendererRegistry,
    DEFAULT_CHAT_RENDERER_REGISTRY,
    register_default_chat_part_renderer,
)


class ChatPromptTemplate:
    """
    Prompt template for chat-style prompts with multimodal content.
    """

    def __init__(
        self,
        messages: List[Dict[str, MessageContent]],
        template_type: PromptType = PromptType.MUSTACHE,
        *,
        registry: Optional[ChatContentRendererRegistry] = None,
    ) -> None:
        self._messages = messages
        self._template_type = template_type
        self._registry = registry or DEFAULT_CHAT_RENDERER_REGISTRY

    @property
    def messages(self) -> List[Dict[str, MessageContent]]:
        return self._messages

    def required_modalities(self) -> ModalitySet:
        """
        Return the union of modalities referenced across all template messages.
        """
        required: ModalitySet = set()
        for message in self._messages:
            content = cast(MessageContent, message.get("content", ""))
            required.update(self._registry.infer_modalities(content))
        return required

    def format(
        self,
        variables: Dict[str, Any],
        supported_modalities: Optional[SupportedModalities] = None,
        *,
        template_type: Optional[Union[str, PromptType]] = None,
    ) -> List[Dict[str, MessageContent]]:
        """
        Render the template messages with the provided variables.

        When a part declares a modality that is not supported, the registry replaces
        it with the configured placeholder pair (for example ``<<<image>>>``) so
        downstream consumers receive a textual anchor while unsupported structured
        content is gracefully elided.
        """
        resolved_template_type = self._registry.normalize_template_type(
            template_type or self._template_type
        )
        rendered_messages: List[Dict[str, MessageContent]] = []

        for message in self._messages:
            role = message.get("role")
            if role is None:
                continue

            content = cast(MessageContent, message.get("content", ""))
            rendered_content: MessageContent
            if isinstance(content, str):
                rendered_content = _render_template_string(
                    content, variables, resolved_template_type
                )
            else:
                rendered_content = self._registry.render_content(
                    content=cast(MessageContent, content),
                    variables=variables,
                    template_type=resolved_template_type,
                    supported_modalities=supported_modalities,
                )
            rendered_messages.append(
                {
                    "role": role,
                    "content": rendered_content,
                }
            )

        return rendered_messages


def _render_template_string(
    template: str,
    variables: Dict[str, Any],
    template_type: PromptType,
) -> str:
    if not template:
        return ""

    try:
        return PromptTemplate(
            template,
            validate_placeholders=False,
            type=template_type,
        ).format(**variables)
    except Exception:
        # Fall back to the raw template if formatting fails so evaluation keeps running.
        return template


def render_text_part(
    part: ContentPart, variables: Dict[str, Any], template_type: PromptType
) -> Optional[ContentPart]:
    text_template = part.get("text", "")
    rendered_text = _render_template_string(text_template, variables, template_type)
    return {"type": "text", "text": rendered_text}


def render_image_url_part(
    part: ContentPart, variables: Dict[str, Any], template_type: PromptType
) -> Optional[ContentPart]:
    image_dict = part.get("image_url", {})
    if not isinstance(image_dict, dict):
        return None

    url_template = image_dict.get("url", "")
    rendered_url = _render_template_string(url_template, variables, template_type)
    if not rendered_url:
        return None

    rendered_image: Dict[str, Any] = {"url": rendered_url}
    if "detail" in image_dict:
        rendered_image["detail"] = image_dict["detail"]

    return {"type": "image_url", "image_url": rendered_image}


def render_video_url_part(
    part: ContentPart, variables: Dict[str, Any], template_type: PromptType
) -> Optional[ContentPart]:
    """
    Render a ``video_url`` part and preserve optional metadata.

    In addition to the rendered ``url`` we keep:

    - ``detail``: free-form provider hints (mirrors the image renderer semantics).
    - ``mime_type``: the content type callers expect the downstream model to load.
    - ``duration``: client-supplied duration in seconds to give hosts extra context.
    - ``format``: a short format label (``mp4``, ``webm``, etc.) when known.
    """
    video_dict = part.get("video_url", {})
    if not isinstance(video_dict, dict):
        return None

    url_template = video_dict.get("url", "")
    rendered_url = _render_template_string(url_template, variables, template_type)
    if not rendered_url:
        return None

    rendered_video: Dict[str, Any] = {"url": rendered_url}
    for key in ("detail", "mime_type", "duration", "format"):
        if key in video_dict:
            rendered_video[key] = video_dict[key]

    return {"type": "video_url", "video_url": rendered_video}


register_default_chat_part_renderer("text", render_text_part)
register_default_chat_part_renderer(
    "image_url",
    render_image_url_part,
    modality="vision",
    placeholder=("<<<image>>>", "<<</image>>>"),
)
register_default_chat_part_renderer(
    "video_url",
    render_video_url_part,
    modality="video",
    placeholder=("<<<video>>>", "<<</video>>>"),
)


__all__ = [
    "ChatPromptTemplate",
    "register_default_chat_part_renderer",
]

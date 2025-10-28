"""
Utilities for rendering evaluation message content with multimodal support.

The renderer accepts both plain string prompts and OpenAI-style structured
content. When a model does not support vision inputs, image references are
flattened into textual placeholders so downstream tooling can still inspect
which images were referenced.
"""

from typing import Any, Dict, List, Union

from opik.api_objects.prompt.prompt_template import PromptTemplate

MessageContent = Union[str, List[Dict[str, Any]]]


# Placeholders used when flattening structured image content for text-only models
_IMAGE_PLACEHOLDER_PREFIX = "<<<image>>>"
_IMAGE_PLACEHOLDER_SUFFIX = "<<</image>>>"


def render_message_content(
    content: MessageContent,
    variables: Dict[str, Any],
    supports_vision: bool,
    template_type: str = "mustache",
) -> MessageContent:
    """
    Render a message body, preserving multimodal parts when supported.

    Args:
        content: Message content definition. Either a plain string or a list of
            OpenAI-style content blocks (dictionaries with ``type`` keys).
        variables: Template variables used when formatting dynamic fields.
        supports_vision: Whether the downstream model accepts image inputs.
        template_type: Rendering mode accepted by :class:`PromptTemplate`.

    Returns:
        The rendered message content using the same structure as the input when
        ``supports_vision`` is True. Otherwise, structured content is converted
        into a newline-separated string with image placeholders.
    """
    if isinstance(content, str):
        return _render_template_string(content, variables, template_type)

    if not isinstance(content, list):
        return str(content)

    if not content:
        return []

    rendered_parts: List[Dict[str, Any]] = []
    for part in content:
        if not isinstance(part, dict):
            continue

        part_type = part.get("type", "").lower()

        if part_type == "text":
            text_template = part.get("text", "")
            rendered_parts.append(
                {
                    "type": "text",
                    "text": _render_template_string(
                        text_template, variables, template_type
                    ),
                }
            )
        elif part_type == "image_url":
            image_dict = part.get("image_url", {})
            if not isinstance(image_dict, dict):
                continue

            rendered_image: Dict[str, Any] = {}
            url_template = image_dict.get("url", "")
            rendered_image["url"] = _render_template_string(
                url_template, variables, template_type
            )

            if "detail" in image_dict:
                rendered_image["detail"] = image_dict["detail"]

            rendered_parts.append(
                {
                    "type": "image_url",
                    "image_url": rendered_image,
                }
            )

    if not supports_vision:
        return _flatten_parts_to_text(rendered_parts)

    return rendered_parts


def _render_template_string(
    template: str,
    variables: Dict[str, Any],
    template_type: str,
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
        # Fall back to the raw template if formatting fails so evaluation keeps running
        return template


def _flatten_parts_to_text(parts: List[Dict[str, Any]]) -> str:
    """
    Convert structured content into a newline-separated string with image markers.
    """
    segments: List[str] = []
    for part in parts:
        part_type = part.get("type", "").lower()

        if part_type == "text":
            text = part.get("text", "")
            if text:
                segments.append(text)
        elif part_type == "image_url":
            image = part.get("image_url", {})
            if not isinstance(image, dict):
                continue
            url = image.get("url", "")
            if url:
                segments.append(f"{_IMAGE_PLACEHOLDER_PREFIX}{url}{_IMAGE_PLACEHOLDER_SUFFIX}")

    return "\n\n".join(segments)


__all__ = [
    "MessageContent",
    "render_message_content",
]

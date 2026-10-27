"""Multimodal content helpers for display and logging."""

from __future__ import annotations

from typing import Any
import re

import rich.text
from ..api_objects import types as api_types


def _has_non_empty_message_content(content: Any) -> bool:
    """Return True when message content has a non-empty payload."""
    if isinstance(content, str):
        return bool(content.strip())
    if not isinstance(content, list):
        return False
    if not content:
        return False
    for part in content:
        if isinstance(part, api_types.TextContentPart):
            if part.text.strip():
                return True
            continue
        if isinstance(part, api_types.ImageContentPart):
            if part.image_url.url.strip():
                return True
            continue
        if not isinstance(part, dict):
            continue
        part_type = part.get("type")
        if part_type == "text":
            text = part.get("text")
            if isinstance(text, str) and text.strip():
                return True
        elif part_type == "image_url":
            image_url = part.get("image_url", {})
            if isinstance(image_url, dict):
                url = image_url.get("url")
                if isinstance(url, str) and url.strip():
                    return True
        else:
            for value in part.values():
                if isinstance(value, str) and value.strip():
                    return True
                if isinstance(value, dict):
                    for nested in value.values():
                        if isinstance(nested, str) and nested.strip():
                            return True
    return False


def _extract_url_from_part(part: dict[str, Any], field_name: str) -> str:
    """
    Extract URL from a content part.

    Args:
        part: Content part dictionary.
        field_name: Name of the field containing URL data (e.g., "image_url", "audio_url").

    Returns:
        Extracted URL string, or empty string if not found.
    """
    url_data = part.get(field_name, {})
    return url_data.get("url", "") if isinstance(url_data, dict) else ""


def _format_url_for_rich_display(
    url: str, part_type: str, data_prefix: str
) -> rich.text.Text:
    """
    Format a URL for Rich display.

    Args:
        url: URL to format.
        part_type: Type of content part (e.g., "image_url", "audio_url").
        data_prefix: Data URL prefix (e.g., "data:image", "data:audio").

    Returns:
        Formatted Text object for Rich display.
    """
    if not url:
        return rich.text.Text(
            f"{part_type}:\n  | <no URL>", overflow="fold", style="dim"
        )

    if url.startswith(data_prefix):
        if "," in url:
            base64_part = url.split(",", 1)[1]
            preview = base64_part[:10] + "..." if len(base64_part) > 10 else base64_part
            return rich.text.Text(
                f"{part_type}:\n  | {preview}",
                overflow="fold",
                style="dim",
            )
        else:
            return rich.text.Text(
                f"{part_type}:\n  | {url[:50]}...",
                overflow="fold",
                style="dim",
            )
    else:
        display_url = url[:80] + "..." if len(url) > 80 else url
        return rich.text.Text(
            f"{part_type}:\n  | {display_url}",
            overflow="fold",
            style="dim",
        )


def _format_url_for_string(url: str, part_type: str, data_prefix: str) -> str:
    """
    Format a URL for string representation.

    Args:
        url: URL to format.
        part_type: Type of content part (e.g., "image_url", "audio_url").
        data_prefix: Data URL prefix (e.g., "data:image", "data:audio").

    Returns:
        Formatted string representation.
    """
    if not url:
        return f"[{part_type}] <no URL>"

    if url.startswith(data_prefix):
        if "," in url:
            base64_part = url.split(",", 1)[1]
            preview = base64_part[:20] + "..." if len(base64_part) > 20 else base64_part
            return f"[{part_type}] {data_prefix}/...;base64,{preview}"
        else:
            return f"[{part_type}] {url[:50]}..."
    else:
        display_url = url[:80] + "..." if len(url) > 80 else url
        return f"[{part_type}] {display_url}"


def format_message_content(content: str | list[dict[str, Any]]) -> rich.text.Text:
    """
    Format message content for display, handling string and multimodal content.

    Args:
        content: Message content, either a string or a list of text/image/audio/video parts.

    Returns:
        Text object ready for Rich display.
    """
    if isinstance(content, str):
        return rich.text.Text(content, overflow="fold")

    formatted_parts: list[rich.text.Text] = []
    for part in content:
        part_type = part.get("type")
        if part_type == "text":
            text_content = part.get("text", "")
            if text_content:
                formatted_parts.append(rich.text.Text(text_content, overflow="fold"))
        elif part_type == "image_url":
            url = _extract_url_from_part(part, "image_url")
            formatted_parts.append(
                _format_url_for_rich_display(url, "image_url", "data:image")
            )
        elif part_type == "audio_url":
            url = _extract_url_from_part(part, "audio_url")
            formatted_parts.append(
                _format_url_for_rich_display(url, "audio_url", "data:audio")
            )
        elif part_type == "video_url":
            url = _extract_url_from_part(part, "video_url")
            formatted_parts.append(
                _format_url_for_rich_display(url, "video_url", "data:video")
            )

    if not formatted_parts:
        return rich.text.Text("(empty content)", style="dim")

    result = rich.text.Text()
    for idx, text_part in enumerate(formatted_parts):
        if idx > 0:
            result.append("\n\n")
        result.append(text_part)
    return result


def text_only_messages(messages: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Build a text summary for display or history; never use it as native model input."""
    return [
        {
            **message,
            "content": api_types.extract_text_from_content(message.get("content", "")),
        }
        for message in messages
    ]


_INLINE_MEDIA = re.compile(r"data:[^\s\"<>]*?;base64,[A-Za-z0-9+/=]+")


def redact_inline_media(value: Any) -> Any:
    """Return a text-safe summary for display, history and textual feedback.

    Inline base64 media (data: URLs) becomes a short "[media]" marker; structure,
    text and placeholder URLs such as "{image}" are kept. Native model requests are assembled separately by core.reasoning; original
    messages and media are never changed by this summary.
    """
    if isinstance(value, str):
        return _INLINE_MEDIA.sub("[media]", value)
    if isinstance(value, list):
        return [redact_inline_media(item) for item in value]
    if isinstance(value, dict):
        return {key: redact_inline_media(item) for key, item in value.items()}
    return value

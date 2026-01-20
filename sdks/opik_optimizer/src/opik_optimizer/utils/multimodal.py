"""Multimodal content helpers for display and logging."""

from __future__ import annotations

from typing import Any

import rich.text


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


def content_to_string(content: str | list[dict[str, Any]]) -> str:
    """
    Convert message content to a string representation for diff display.

    Args:
        content: Message content, either a string or a list of content parts.

    Returns:
        String representation of the content.
    """
    if isinstance(content, str):
        return content

    parts: list[str] = []
    for part in content:
        part_type = part.get("type")
        if part_type == "text":
            text_content = part.get("text", "")
            if text_content:
                parts.append(f"[text] {text_content}")
        elif part_type == "image_url":
            url = _extract_url_from_part(part, "image_url")
            parts.append(_format_url_for_string(url, "image_url", "data:image"))
        elif part_type == "audio_url":
            url = _extract_url_from_part(part, "audio_url")
            parts.append(_format_url_for_string(url, "audio_url", "data:audio"))
        elif part_type == "video_url":
            url = _extract_url_from_part(part, "video_url")
            parts.append(_format_url_for_string(url, "video_url", "data:video"))

    return "\n".join(parts) if parts else "(empty content)"

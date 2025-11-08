from __future__ import annotations

from typing import Any, Sequence

MessageContent = str | list[dict[str, Any]]


def extract_text_from_content(content: MessageContent | Any) -> str:
    """
    Extract plain text from a message content payload.

    Args:
        content: Either a string or a list of structured multimodal parts.

    Returns:
        Concatenated text extracted from the content.
    """
    if isinstance(content, str):
        return content

    if isinstance(content, list):
        text_parts: list[str] = []
        for part in content:
            if isinstance(part, dict) and part.get("type") == "text":
                text_parts.append(str(part.get("text", "")))
        return " ".join(text_parts).strip()

    return str(content)


def _content_has_image(content: Any) -> bool:
    if isinstance(content, list):
        for part in content:
            if isinstance(part, dict) and part.get("type") == "image_url":
                return True
    elif isinstance(content, str):
        lowered = content.lower()
        return "{image" in lowered or "<<<image>>>" in content
    return False


def is_multimodal_prompt(
    messages: Sequence[dict[str, Any]] | str | None,
) -> bool:
    """
    Detect whether a prompt contains multimodal (image) content.

    Args:
        messages: Chat messages or raw string content.

    Returns:
        True if any image content or placeholder is detected.
    """
    if messages is None:
        return False

    if isinstance(messages, str):
        return _content_has_image(messages)

    for message in messages:
        if not isinstance(message, dict):
            continue
        content = message.get("content")
        if _content_has_image(content):
            return True

    return False

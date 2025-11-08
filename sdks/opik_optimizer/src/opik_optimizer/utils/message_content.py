from __future__ import annotations

from typing import Any
from collections.abc import Sequence

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


def rebuild_content_with_text(
    original_content: MessageContent,
    mutated_text: str,
) -> MessageContent:
    """
    Rebuild message content with new text while preserving image parts.

    Args:
        original_content: Original message content (string or multimodal list)
        mutated_text: Replacement text to inject

    Returns:
        MessageContent containing the mutated text and any preserved image parts.
    """
    if isinstance(original_content, str):
        return mutated_text

    if isinstance(original_content, list):
        result_parts: list[dict[str, Any]] = []
        result_parts.append({"type": "text", "text": mutated_text})
        for part in original_content:
            if isinstance(part, dict) and part.get("type") == "image_url":
                result_parts.append(part)
        return result_parts

    return mutated_text


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

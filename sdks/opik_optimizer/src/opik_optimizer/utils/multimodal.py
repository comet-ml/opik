"""Multimodal content helpers for display and logging."""

from __future__ import annotations

from typing import Any

import rich.text


def format_message_content(content: str | list[dict[str, Any]]) -> rich.text.Text:
    """
    Format message content for display, handling string and multimodal content.

    Args:
        content: Message content, either a string or a list of text/image parts.

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
                lines = text_content.split("\n")
                formatted_lines: list[str] = ["text:"]
                for line in lines:
                    formatted_lines.append(f"  | {line}")
                formatted_parts.append(
                    rich.text.Text("\n".join(formatted_lines), overflow="fold")
                )
        elif part_type == "image_url":
            image_url_data = part.get("image_url", {})
            url = (
                image_url_data.get("url", "")
                if isinstance(image_url_data, dict)
                else ""
            )
            if url:
                if url.startswith("data:image"):
                    if "," in url:
                        base64_part = url.split(",", 1)[1]
                        preview = (
                            base64_part[:10] + "..."
                            if len(base64_part) > 10
                            else base64_part
                        )
                        formatted_parts.append(
                            rich.text.Text(
                                f"image_url:\n  | {preview}",
                                overflow="fold",
                                style="dim",
                            )
                        )
                    else:
                        formatted_parts.append(
                            rich.text.Text(
                                f"image_url:\n  | {url[:50]}...",
                                overflow="fold",
                                style="dim",
                            )
                        )
                else:
                    display_url = url[:80] + "..." if len(url) > 80 else url
                    formatted_parts.append(
                        rich.text.Text(
                            f"image_url:\n  | {display_url}",
                            overflow="fold",
                            style="dim",
                        )
                    )
            else:
                formatted_parts.append(
                    rich.text.Text(
                        "image_url:\n  | <no URL>", overflow="fold", style="dim"
                    )
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
            image_url_data = part.get("image_url", {})
            url = (
                image_url_data.get("url", "")
                if isinstance(image_url_data, dict)
                else ""
            )
            if url:
                if url.startswith("data:image"):
                    if "," in url:
                        base64_part = url.split(",", 1)[1]
                        preview = (
                            base64_part[:20] + "..."
                            if len(base64_part) > 20
                            else base64_part
                        )
                        parts.append(f"[image_url] data:image/...;base64,{preview}")
                    else:
                        parts.append(f"[image_url] {url[:50]}...")
                else:
                    display_url = url[:80] + "..." if len(url) > 80 else url
                    parts.append(f"[image_url] {display_url}")
            else:
                parts.append("[image_url] <no URL>")

    return "\n".join(parts) if parts else "(empty content)"

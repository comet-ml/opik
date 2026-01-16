"""Display helpers for formatting prompts and numbers."""

from __future__ import annotations

from typing import Any, cast

from ..api_objects import chat_prompt
from .reporting import format_prompt_snippet


def format_float(value: Any, digits: int = 6) -> str:
    """Format float values with specified precision."""
    if isinstance(value, float):
        return f"{value:.{digits}f}"
    return str(value)


def format_prompt_for_plaintext(
    prompt: chat_prompt.ChatPrompt | dict[str, chat_prompt.ChatPrompt],
) -> str:
    """Format a prompt (single or dict) for plain text display."""
    if isinstance(prompt, dict):
        prompt_dict = cast(dict[str, chat_prompt.ChatPrompt], prompt)
        parts: list[str] = []
        for key, chat_p in prompt_dict.items():
            parts.append(f"[{key}]")
            for msg in chat_p.get_messages():
                role = msg.get("role", "unknown")
                content = msg.get("content", "")
                if isinstance(content, str):
                    snippet = format_prompt_snippet(content, max_length=150)
                else:
                    snippet = "[multimodal content]"
                parts.append(f"  {role}: {snippet}")
        return "\n".join(parts)

    parts = []
    for msg in prompt.get_messages():
        role = msg.get("role", "unknown")
        content = msg.get("content", "")
        if isinstance(content, str):
            snippet = format_prompt_snippet(content, max_length=150)
        else:
            snippet = "[multimodal content]"
        parts.append(f"  {role}: {snippet}")
    return "\n".join(parts)

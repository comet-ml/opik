"""
Small helpers for constructing chat message dicts in tests.

These reduce boilerplate like:
    {"role": "system", "content": "..."}
and make intent clearer at call sites.
"""

from __future__ import annotations

from typing import Any

Message = dict[str, Any]


def system_message(content: Any, **extra: Any) -> Message:
    return {"role": "system", "content": content, **extra}


def user_message(content: Any, **extra: Any) -> Message:
    return {"role": "user", "content": content, **extra}


def assistant_message(content: Any, **extra: Any) -> Message:
    return {"role": "assistant", "content": content, **extra}


def tool_message(content: Any, **extra: Any) -> Message:
    return {"role": "tool", "content": content, **extra}


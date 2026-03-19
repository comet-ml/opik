"""Parsing helpers for Anthropic API responses."""

from __future__ import annotations

import json
from typing import Any, Optional


def extract_text_content(response: Any) -> Optional[str]:
    for block in response.content:
        if getattr(block, "type", None) == "text":
            return block.text
    return None


def extract_tool_use_content(response: Any) -> Optional[str]:
    for block in response.content:
        if getattr(block, "type", None) == "tool_use":
            return json.dumps(block.input)
    return None

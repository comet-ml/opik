"""Compatibility shim for core utility helpers.

This module intentionally re-exports implementations from ``tool_helpers`` so
there is a single source of truth for tool helper behavior.
"""

from __future__ import annotations

from .tool_helpers import (
    deep_merge_dicts,
    describe_annotation,
    serialize_tools,
    summarize_tool_signatures,
)

__all__ = [
    "deep_merge_dicts",
    "serialize_tools",
    "describe_annotation",
    "summarize_tool_signatures",
]

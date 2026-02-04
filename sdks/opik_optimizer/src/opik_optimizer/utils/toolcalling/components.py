"""Shared tool component key helpers for optimizers."""

from __future__ import annotations

TOOL_COMPONENT_PREFIX = "__tool::"
TOOL_PARAM_COMPONENT_PREFIX = "__tool_param::"


def tool_component_key(prompt_name: str, tool_name: str) -> str:
    return f"{prompt_name}{TOOL_COMPONENT_PREFIX}{tool_name}"


def tool_param_component_key(prompt_name: str, tool_name: str, param_name: str) -> str:
    return f"{prompt_name}{TOOL_PARAM_COMPONENT_PREFIX}{tool_name}::{param_name}"

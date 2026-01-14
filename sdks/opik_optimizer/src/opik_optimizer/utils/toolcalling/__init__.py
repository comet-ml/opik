"""Helpers for tool-calling workflows and signature management."""

from .mcp import (
    ToolCallingDependencyError,
    ToolCallingManifest,
    ToolSignature,
    call_tool_from_manifest,
    dump_mcp_signature,
    list_tools_from_manifest,
    load_mcp_signature,
    load_tool_signature_from_manifest,
    response_to_text,
    system_prompt_from_tool,
    tools_from_signatures,
    validate_tool_arguments,
)
from .tool_factory import ToolCallingFactory, resolve_toolcalling_tools
from .toolcalling import generate_tool_description_candidates, prepare_tool_optimization
from . import prompts as toolcalling_prompts

__all__ = [
    "ToolCallingDependencyError",
    "ToolCallingManifest",
    "ToolSignature",
    "ToolCallingFactory",
    "call_tool_from_manifest",
    "dump_mcp_signature",
    "list_tools_from_manifest",
    "load_mcp_signature",
    "load_tool_signature_from_manifest",
    "response_to_text",
    "resolve_toolcalling_tools",
    "generate_tool_description_candidates",
    "prepare_tool_optimization",
    "system_prompt_from_tool",
    "tools_from_signatures",
    "validate_tool_arguments",
    "toolcalling_prompts",
]

"""Helpers for tool-calling workflows and signature management."""

from .runtime.mcp import (
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
from .normalize.tool_factory import (
    ToolCallingFactory,
    cursor_mcp_config_to_tools,
    resolve_toolcalling_tools,
)
from .core.metadata import extract_tool_descriptions
from .ops.toolcalling import (
    build_tool_blocks_from_prompt,
    generate_tool_description_candidates,
    prepare_tool_optimization,
    resolve_prompt_tools,
    should_allow_tool_use,
    report_tool_descriptions,
    make_tool_description_reporter,
)

__all__ = [
    "ToolCallingDependencyError",
    "ToolCallingManifest",
    "ToolSignature",
    "ToolCallingFactory",
    "cursor_mcp_config_to_tools",
    "extract_tool_descriptions",
    "call_tool_from_manifest",
    "dump_mcp_signature",
    "list_tools_from_manifest",
    "load_mcp_signature",
    "load_tool_signature_from_manifest",
    "response_to_text",
    "resolve_toolcalling_tools",
    "generate_tool_description_candidates",
    "build_tool_blocks_from_prompt",
    "prepare_tool_optimization",
    "resolve_prompt_tools",
    "should_allow_tool_use",
    "report_tool_descriptions",
    "make_tool_description_reporter",
    "system_prompt_from_tool",
    "tools_from_signatures",
    "validate_tool_arguments",
]

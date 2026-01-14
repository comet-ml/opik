"""Factory helpers to resolve MCP tool entries into function-calling tools.

The factory normalizes user-supplied ``tools`` entries that include an ``mcp``
block into standard function-calling tools, and returns callables for the
tool loop. MCP tool metadata remains attached to the tool entry so optimizers
can filter or mutate MCP tools separately from standard function tools.
"""

from __future__ import annotations

import copy
import json
import logging
from dataclasses import dataclass
from typing import Any
from collections.abc import Callable, Mapping

from opik_optimizer.api_objects import chat_prompt
from .mcp import (
    ToolCallingDependencyError,
    ToolCallingManifest,
    ToolSignature,
    call_tool_from_manifest,
    load_tool_signature_from_manifest,
    response_to_text,
)

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class ToolCallingResolvedTool:
    """Container for a resolved tool entry and its callable."""

    function_entry: dict[str, Any]
    function_name: str
    callable: Callable[..., str]


class ToolCallingFactory:
    """Resolve MCP tool entries into standard function-calling tools.

    The factory loads tool signatures (stdio or remote) and emits
    function-calling tool entries plus a callable that executes the tool.
    """

    def __init__(self) -> None:
        self._signature_cache: dict[str, ToolSignature] = {}

    def resolve_prompt(
        self,
        prompt: chat_prompt.ChatPrompt,
    ) -> chat_prompt.ChatPrompt:
        """Return a new ChatPrompt with tool entries normalized and mapped."""
        if not prompt.tools:
            return prompt
        resolved_tools, resolved_map = resolve_toolcalling_tools(
            prompt.tools, prompt.function_map, factory=self
        )
        return chat_prompt.ChatPrompt(
            name=prompt.name,
            system=prompt.system,
            user=prompt.user,
            messages=copy.deepcopy(prompt.messages),
            tools=resolved_tools,
            function_map=resolved_map,
            model=prompt.model,
            model_parameters=copy.deepcopy(prompt.model_kwargs or {}),
        )

    def resolve_tool_entry(self, entry: Mapping[str, Any]) -> ToolCallingResolvedTool:
        """Resolve a single tool entry into a function tool plus callable."""
        mcp_block = entry.get("mcp")
        if not isinstance(mcp_block, Mapping):
            raise ValueError("MCP tool entry missing 'mcp' block")

        server = mcp_block.get("server")
        tool_block = mcp_block.get("tool")
        if not isinstance(server, Mapping):
            raise ValueError("MCP tool entry missing 'mcp.server'")
        if not isinstance(tool_block, Mapping):
            raise ValueError("MCP tool entry missing 'mcp.tool'")

        tool_name = tool_block.get("name")
        if not tool_name:
            raise ValueError("MCP tool entry missing 'mcp.tool.name'")

        function_name = mcp_block.get("name")
        if not function_name:
            raise ValueError("MCP tool entry missing 'mcp.name'")
        signature = self._get_signature(server, tool_name, mcp_block.get("signature"))

        function_entry: dict[str, Any] = {
            "type": "function",
            "function": {
                "name": function_name,
                "description": signature.description,
                "parameters": signature.parameters,
            },
            "mcp": copy.deepcopy(dict(mcp_block)),
        }

        _callable = self._build_callable(server, tool_name)

        return ToolCallingResolvedTool(
            function_entry=function_entry,
            function_name=function_name,
            callable=_callable,
        )

    def _build_callable(
        self, server: Mapping[str, Any], tool_name: str
    ) -> Callable[..., str]:
        """Build a callable that executes the MCP tool and returns text output."""
        def _callable(**arguments: Any) -> str:
            response = self._call_tool(server, tool_name, arguments)
            return response_to_text(response)

        return _callable

    def _cache_key(self, server: Mapping[str, Any], tool_name: str) -> str:
        """Build a stable cache key for a server+tool signature lookup."""
        server_payload = json.dumps(server, sort_keys=True, default=str)
        return f"{server_payload}::{tool_name}"

    def _get_signature(
        self,
        server: Mapping[str, Any],
        tool_name: str,
        signature_override: Mapping[str, Any] | None,
    ) -> ToolSignature:
        """Load or override the tool signature for the given server/tool."""
        if signature_override is not None:
            return ToolSignature.from_tool_entry(
                {
                    "type": "function",
                    "function": {
                        "name": signature_override.get("name", tool_name),
                        "description": signature_override.get("description", ""),
                        "parameters": signature_override.get("parameters", {}),
                    },
                }
            )

        cache_key = self._cache_key(server, tool_name)
        cached = self._signature_cache.get(cache_key)
        if cached is not None:
            return cached

        server_type = server.get("type")
        if server_type == "stdio":
            manifest = ToolCallingManifest.from_dict(server)
            signature = load_tool_signature_from_manifest(manifest, tool_name)
        elif server_type == "remote":
            signature = _load_remote_tool_signature(server, tool_name)
        else:
            raise ValueError("MCP server type must be 'stdio' or 'remote'")

        self._signature_cache[cache_key] = signature
        return signature

    def _call_tool(
        self, server: Mapping[str, Any], tool_name: str, arguments: Mapping[str, Any]
    ) -> Any:
        """Invoke the MCP tool against the configured server."""
        server_type = server.get("type")
        if server_type == "stdio":
            manifest = ToolCallingManifest.from_dict(server)
            return call_tool_from_manifest(manifest, tool_name, dict(arguments))
        if server_type == "remote":
            return _call_remote_tool(server, tool_name, dict(arguments))
        raise ValueError("MCP server type must be 'stdio' or 'remote'")


def resolve_toolcalling_tools(
    tools: list[dict[str, Any]],
    function_map: dict[str, Callable] | None,
    *,
    factory: ToolCallingFactory | None = None,
) -> tuple[list[dict[str, Any]], dict[str, Callable]]:
    """Normalize tool entries and return tools + function map for calling."""
    resolved_tools: list[dict[str, Any]] = []
    resolved_map = dict(function_map or {})
    factory = factory or ToolCallingFactory()

    for tool in tools:
        if not isinstance(tool, dict) or "mcp" not in tool:
            resolved_tools.append(copy.deepcopy(tool))
            continue

        if "function" in tool and tool.get("type") == "function":
            function_name = tool.get("function", {}).get("name")
            if function_name:
                resolved_tools.append(copy.deepcopy(tool))
                if function_name not in resolved_map:
                    mcp_block = tool.get("mcp", {})
                    tool_block = (
                        mcp_block.get("tool", {}) if isinstance(mcp_block, dict) else {}
                    )
                    mcp_tool_name = tool_block.get("name")
                    server = mcp_block.get("server")
                    if mcp_tool_name and isinstance(server, dict):
                        resolved_map[function_name] = factory._build_callable(
                            server, mcp_tool_name
                        )
                continue

        resolved = factory.resolve_tool_entry(tool)
        resolved_tools.append(resolved.function_entry)
        resolved_map.setdefault(resolved.function_name, resolved.callable)

    return resolved_tools, resolved_map


def _load_remote_tool_signature(
    server: Mapping[str, Any], tool_name: str
) -> ToolSignature:
    url = server.get("url")
    headers = server.get("headers") or {}
    if not url:
        raise ValueError("Remote MCP server missing 'url'")

    tools = list_tools_from_remote(url, headers)
    tool = next(
        (tool for tool in tools if getattr(tool, "name", None) == tool_name), None
    )
    if tool is None:
        raise ValueError(f"Tool '{tool_name}' not found on remote MCP server")
    entry = tool.model_dump(by_alias=True)
    annotations = entry.get("annotations") or {}
    examples = annotations.get("examples")
    return ToolSignature.from_tool_entry(
        {
            "type": "function",
            "function": {
                "name": entry.get("name", tool_name),
                "description": entry.get("description", ""),
                "parameters": entry.get("inputSchema", {}),
                "examples": examples,
            },
        }
    )


def _call_remote_tool(
    server: Mapping[str, Any], tool_name: str, arguments: dict[str, Any]
) -> Any:
    url = server.get("url")
    headers = server.get("headers") or {}
    if not url:
        raise ValueError("Remote MCP server missing 'url'")
    return call_tool_from_remote(url, headers, tool_name, arguments)


def list_tools_from_remote(url: str, headers: Mapping[str, str]) -> Any:
    """List tools from a remote MCP server using StreamableHTTP."""
    try:
        from .mcp_remote import list_tools_from_remote as _list
    except ToolCallingDependencyError:
        raise
    return _list(url, dict(headers))


def call_tool_from_remote(
    url: str, headers: Mapping[str, str], tool_name: str, arguments: dict[str, Any]
) -> Any:
    """Call a remote MCP tool via StreamableHTTP."""
    try:
        from .mcp_remote import call_tool_from_remote as _call
    except ToolCallingDependencyError:
        raise
    return _call(url, dict(headers), tool_name, arguments)

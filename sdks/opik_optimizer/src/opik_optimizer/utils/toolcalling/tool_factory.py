"""Factory helpers to resolve MCP tool entries into function-calling tools.

OpenAI-style tools:
    {
      "type": "mcp",
      "server_label": "context7",
      "server_url": "https://mcp.context7.com/mcp",
      "headers": {"CONTEXT7_API_KEY": "YOUR_API_KEY"},
      "allowed_tools": ["resolve-library-id", "get-library-docs"]
    }

Cursor-style configs can be converted with ``cursor_mcp_config_to_tools``:
    {
      "mcpServers": {
        "context7": {
          "command": "npx",
          "args": ["-y", "@upstash/context7-mcp", "--api-key", "YOUR_API_KEY"]
        }
      }
    }
"""

from __future__ import annotations

import copy
import json
import logging
import os
import warnings
from dataclasses import dataclass
from typing import Any
from collections.abc import Callable, Mapping

from opik_optimizer.api_objects import chat_prompt
from .mcp import (
    ToolCallingDependencyError,
    ToolCallingManifest,
    ToolSignature,
    call_tool_from_manifest,
    list_tools_from_manifest,
    load_tool_signature_from_manifest,
    response_to_text,
)

logger = logging.getLogger(__name__)
_REQUIRE_APPROVAL_WARNED = False


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
        original_tools = copy.deepcopy(prompt.tools)
        resolved_tools, resolved_map = resolve_toolcalling_tools(
            prompt.tools, prompt.function_map, factory=self
        )
        resolved_prompt = chat_prompt.ChatPrompt(
            name=prompt.name,
            system=prompt.system,
            user=prompt.user,
            messages=copy.deepcopy(prompt.messages),
            tools=resolved_tools,
            function_map=resolved_map,
            model=prompt.model,
            model_parameters=copy.deepcopy(prompt.model_kwargs or {}),
        )
        setattr(resolved_prompt, "tools_original", original_tools)
        return resolved_prompt

    def resolve_tool_entry(self, entry: Mapping[str, Any]) -> ToolCallingResolvedTool:
        """Resolve a single legacy MCP tool entry into a function tool plus callable."""
        mcp_block = entry.get("mcp")
        if not isinstance(mcp_block, Mapping):
            raise ValueError("Legacy MCP tool entry missing 'mcp' block")

        server = mcp_block.get("server")
        tool_block = mcp_block.get("tool")
        if not isinstance(server, Mapping):
            raise ValueError("Legacy MCP tool entry missing 'mcp.server'")
        if not isinstance(tool_block, Mapping):
            raise ValueError("Legacy MCP tool entry missing 'mcp.tool'")

        tool_name = tool_block.get("name")
        if not tool_name:
            raise ValueError("Legacy MCP tool entry missing 'mcp.tool.name'")

        function_name = mcp_block.get("name")
        if not function_name:
            raise ValueError("Legacy MCP tool entry missing 'mcp.name'")
        signature = self._get_signature(server, tool_name, mcp_block.get("signature"))

        parameters = _strip_schema_field(signature.parameters)
        function_entry: dict[str, Any] = {
            "type": "function",
            "function": {
                "name": function_name,
                "description": signature.description,
                "parameters": parameters,
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
    occupied_names = _collect_function_names(tools, resolved_map)

    for tool in tools:
        if not isinstance(tool, dict):
            resolved_tools.append(copy.deepcopy(tool))
            continue

        if tool.get("type") == "mcp":
            normalized = _normalize_openai_mcp_tool(tool)
            server = normalized["server"]
            server_label = normalized["server_label"]
            allowed_tools = normalized["allowed_tools"]
            require_approval = normalized["require_approval"]

            if allowed_tools is None:
                allowed_tools = _list_tool_names(server)
            if not allowed_tools:
                raise ValueError(
                    f"MCP server '{server_label}' did not return any tools."
                )

            existing_names = occupied_names
            for tool_name in allowed_tools:
                signature = factory._get_signature(server, tool_name, None)
                function_name = _resolve_function_name(
                    tool_name, server_label, existing_names
                )
                existing_names.add(function_name)
                occupied_names.add(function_name)
                parameters = _strip_schema_field(signature.parameters)
                function_entry = {
                    "type": "function",
                    "function": {
                        "name": function_name,
                        "description": signature.description,
                        "parameters": parameters,
                    },
                    "mcp": {
                        "server_label": server_label,
                        "server": copy.deepcopy(server),
                        "tool": {"name": tool_name},
                        "name": function_name,
                        "allowed_tools": allowed_tools,
                        "require_approval": require_approval,
                    },
                }
                resolved_tools.append(function_entry)
                resolved_map.setdefault(
                    function_name, factory._build_callable(server, tool_name)
                )
            continue

        if "mcp" not in tool:
            resolved_tools.append(copy.deepcopy(tool))
            if tool.get("type") == "function":
                function_name = tool.get("function", {}).get("name")
                if function_name:
                    occupied_names.add(function_name)
            continue

        if "function" in tool and tool.get("type") == "function":
            function_name = tool.get("function", {}).get("name")
            if function_name:
                resolved_tools.append(copy.deepcopy(tool))
                occupied_names.add(function_name)
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
            occupied_names.add(resolved.function_name)

    return resolved_tools, resolved_map


def cursor_mcp_config_to_tools(config: Mapping[str, Any]) -> list[dict[str, Any]]:
    """Convert Cursor-style MCP config into OpenAI-style MCP tool entries.

    Cursor style (local):
        {
          "mcpServers": {
            "context7": {
              "command": "npx",
              "args": ["-y", "@upstash/context7-mcp", "--api-key", "YOUR_API_KEY"],
              "env": {"API_KEY": "${env:API_KEY}"}
            }
          }
        }

    Cursor style (remote):
        {
          "mcpServers": {
            "context7": {
              "url": "https://mcp.context7.com/mcp",
              "headers": {"CONTEXT7_API_KEY": "YOUR_API_KEY"}
            }
          }
        }

    OpenAI-style output:
        {
          "tools": [
            {
              "type": "mcp",
              "server_label": "context7",
              "server_url": "https://mcp.context7.com/mcp",
              "headers": {"CONTEXT7_API_KEY": "YOUR_API_KEY"}
            }
          ]
        }
    """
    servers = config.get("mcpServers")
    if not isinstance(servers, Mapping):
        raise ValueError("Cursor MCP config must include 'mcpServers'.")

    def _resolve_env_value(value: Any, env_key: str) -> Any:
        if not isinstance(value, str):
            return value
        if value.startswith("${env:") and value.endswith("}"):
            env_name = value[6:-1]
            return os.environ.get(env_name, "")
        if value == "":
            return os.environ.get(env_key, "")
        return value

    def _resolve_env_mapping(mapping: Mapping[str, Any]) -> dict[str, Any]:
        resolved: dict[str, Any] = {}
        for key, value in mapping.items():
            resolved[key] = _resolve_env_value(value, key)
        return resolved

    tools: list[dict[str, Any]] = []
    for server_label, server in servers.items():
        if not isinstance(server, Mapping):
            raise ValueError(f"MCP server '{server_label}' must be a mapping.")
        entry: dict[str, Any] = {"type": "mcp", "server_label": server_label}
        if "url" in server:
            entry["server_url"] = server.get("url")
            if "headers" in server:
                headers = server.get("headers")
                entry["headers"] = (
                    _resolve_env_mapping(headers)
                    if isinstance(headers, Mapping)
                    else headers
                )
            if "auth" in server:
                auth = server.get("auth")
                entry["auth"] = (
                    _resolve_env_mapping(auth) if isinstance(auth, Mapping) else auth
                )
        else:
            entry["command"] = server.get("command")
            entry["args"] = server.get("args", [])
            env = server.get("env", {})
            entry["env"] = (
                _resolve_env_mapping(env) if isinstance(env, Mapping) else env
            )
        tools.append(entry)

    return tools


def _normalize_openai_mcp_tool(entry: Mapping[str, Any]) -> dict[str, Any]:
    if entry.get("type") != "mcp":
        raise ValueError("MCP tool entry must have type='mcp'.")

    server_label = entry.get("server_label")
    if not server_label and entry.get("name"):
        server_label = entry.get("name")
        warnings.warn(
            "MCP tool entry used 'name' instead of 'server_label'; converting.",
            UserWarning,
            stacklevel=2,
        )
    if not server_label:
        raise ValueError("MCP tool entry missing 'server_label'.")

    server_url = entry.get("server_url") or entry.get("url")
    if entry.get("url") and not entry.get("server_url"):
        warnings.warn(
            "MCP tool entry used 'url' instead of 'server_url'; converting.",
            UserWarning,
            stacklevel=2,
        )
    command = entry.get("command")
    if server_url and command:
        raise ValueError("MCP tool entry cannot include both server_url and command.")
    if not server_url and not command:
        raise ValueError("MCP tool entry must include either server_url or command.")

    require_approval = entry.get("require_approval")
    if require_approval is not None:
        _warn_require_approval()

    if server_url:
        server = {
            "type": "remote",
            "url": server_url,
            "headers": entry.get("headers", {}),
            "auth": entry.get("auth"),
        }
    else:
        server = {
            "type": "stdio",
            "command": command,
            "args": entry.get("args", []),
            "env": entry.get("env", {}),
        }

    allowed_tools = entry.get("allowed_tools")
    if allowed_tools is not None and not isinstance(allowed_tools, list):
        raise ValueError("allowed_tools must be a list of tool names.")

    return {
        "server_label": server_label,
        "server": server,
        "allowed_tools": allowed_tools,
        "require_approval": require_approval,
    }


def _warn_require_approval() -> None:
    global _REQUIRE_APPROVAL_WARNED
    if _REQUIRE_APPROVAL_WARNED:
        return
    warnings.warn(
        "require_approval is ignored by the optimizer toolcalling runtime.",
        UserWarning,
        stacklevel=2,
    )
    _REQUIRE_APPROVAL_WARNED = True


def _collect_function_names(
    tools: list[dict[str, Any]], function_map: dict[str, Callable]
) -> set[str]:
    names = set(function_map.keys())
    for tool in tools:
        if tool.get("type") == "function":
            function_block = tool.get("function", {})
            name = function_block.get("name")
            if name:
                names.add(name)
    return names


def _resolve_function_name(
    tool_name: str, server_label: str, existing_names: set[str]
) -> str:
    if tool_name not in existing_names:
        return tool_name

    candidate = f"{server_label}.{tool_name}"
    suffix = 2
    while candidate in existing_names:
        candidate = f"{server_label}.{tool_name}.{suffix}"
        suffix += 1

    logger.warning(
        "Tool name collision for %s; using %s instead.", tool_name, candidate
    )
    return candidate


def _list_tool_names(server: Mapping[str, Any]) -> list[str]:
    server_type = server.get("type")
    if server_type == "stdio":
        manifest = ToolCallingManifest.from_dict(server)
        tools = list_tools_from_manifest(manifest)
    elif server_type == "remote":
        url = server.get("url")
        if not url:
            raise ValueError("Remote MCP server missing 'url'.")
        tools = list_tools_from_remote(url, server.get("headers") or {})
    else:
        raise ValueError("MCP server type must be 'stdio' or 'remote'")

    names: list[str] = []
    for tool in tools:
        name = getattr(tool, "name", None)
        if name:
            names.append(name)
    return names


def _strip_schema_field(parameters: Mapping[str, Any]) -> dict[str, Any]:
    cleaned = dict(parameters) if parameters else {}
    cleaned.pop("$schema", None)
    return cleaned


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
    response = call_tool_from_remote(url, headers, tool_name, arguments)
    _log_remote_tool_response(tool_name, response)
    return response


def _log_remote_tool_response(tool_name: str, response: Any) -> None:
    text = response_to_text(response)
    if "quota exceeded" in text.lower():
        logger.warning(
            "MCP remote tool quota exceeded name=%s response=%s",
            tool_name,
            _snippet(text),
        )

    meta = _extract_response_meta(response)
    if meta:
        status = (
            meta.get("status")
            or meta.get("status_code")
            or meta.get("http_status")
            or meta.get("httpStatus")
        )
        logger.debug(
            "MCP remote tool meta name=%s status=%s meta=%s",
            tool_name,
            status,
            meta,
        )


def _extract_response_meta(response: Any) -> dict[str, Any] | None:
    meta = getattr(response, "meta", None) or getattr(response, "_meta", None)
    if isinstance(meta, dict) and meta:
        return meta
    content = getattr(response, "content", None)
    if isinstance(content, list):
        for item in content:
            item_meta = getattr(item, "meta", None) or getattr(item, "_meta", None)
            if isinstance(item_meta, dict) and item_meta:
                return item_meta
    return None


def _snippet(text: str, max_length: int = 160) -> str:
    cleaned = " ".join(text.replace("\n", " ").replace("\t", " ").split())
    return cleaned if len(cleaned) <= max_length else f"{cleaned[:max_length]}..."


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

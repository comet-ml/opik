"""Input normalization helpers for toolcalling configuration formats."""

from __future__ import annotations

import os
from typing import Any
from collections.abc import Mapping


def normalize_tools_input(
    tools: list[dict[str, Any]] | Mapping[str, Any] | None,
) -> list[dict[str, Any]] | None:
    """Normalize user-provided tools input into a list of tool entries.

    Accepts:
    - ``None``
    - OpenAI/LiteLLM-style tool list
    - Cursor-style MCP config mapping (``{"mcpServers": ...}``)
    """
    if tools is None:
        return None
    if isinstance(tools, list):
        return tools
    if isinstance(tools, Mapping):
        return cursor_mcp_config_to_tools(tools)
    raise ValueError("`tools` must be a list, Cursor MCP config mapping, or None")


def cursor_mcp_config_to_tools(config: Mapping[str, Any]) -> list[dict[str, Any]]:
    """Convert Cursor-style MCP config into OpenAI-style MCP tool entries."""
    servers = config.get("mcpServers")
    if not isinstance(servers, Mapping):
        raise ValueError("Cursor MCP config must include 'mcpServers'.")

    def _resolve_env_value(value: Any, env_key: str) -> str:
        """Resolve ${env:VAR} tokens or empty values using environment variables."""
        if not isinstance(value, str):
            return str(value)
        if value.startswith("${env:") and value.endswith("}"):
            env_name = value[6:-1]
            return str(os.environ.get(env_name, ""))
        if value == "":
            return str(os.environ.get(env_key, ""))
        return value

    def _resolve_env_mapping(mapping: Mapping[str, Any]) -> dict[str, str]:
        """Resolve env tokens in a mapping for headers/auth/env values."""
        resolved: dict[str, str] = {}
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

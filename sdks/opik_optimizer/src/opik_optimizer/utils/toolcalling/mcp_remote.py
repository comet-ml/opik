"""Remote MCP client helpers using StreamableHTTP transport.

This module uses the MCP Python SDK's streamable HTTP client to connect
to remote MCP servers (see ClientSession + streamablehttp_client). It
mirrors the stdio helpers in ``mcp`` but over HTTP/SSE.
"""

from __future__ import annotations

import asyncio
from typing import Any, TypeVar, cast
from collections.abc import Coroutine, Mapping

from .mcp import ToolCallingDependencyError

_T = TypeVar("_T")


def _load_remote_sdk() -> tuple[Any, Any]:
    """Import the MCP ClientSession + StreamableHTTP transport."""
    try:
        from mcp.client.session import ClientSession  # type: ignore
    except ImportError as exc:  # pragma: no cover - optional dependency
        raise ToolCallingDependencyError(
            "Remote MCP support requires the `mcp` Python SDK with streamable HTTP transport."
        ) from exc
    try:
        from mcp.client.streamable_http import streamablehttp_client  # type: ignore
    except ImportError:
        try:
            from mcp.client.streamablehttp import streamablehttp_client  # type: ignore
        except ImportError as exc:  # pragma: no cover - optional dependency
            raise ToolCallingDependencyError(
                "Remote MCP support requires the `mcp` Python SDK with streamable HTTP transport."
            ) from exc
    return ClientSession, streamablehttp_client


def _run_sync(coro: Coroutine[Any, Any, _T]) -> _T:
    """Run an async MCP coroutine in a fresh event loop."""
    return asyncio.run(coro)


def list_tools_from_remote(url: str, headers: Mapping[str, str]) -> Any:
    """List tools from a remote MCP server over StreamableHTTP."""
    async def _inner() -> Any:
        ClientSession, streamablehttp_client = _load_remote_sdk()
        async with streamablehttp_client(url, headers=dict(headers)) as (
            read_stream,
            write_stream,
            _get_session_id,
        ):
            session = cast(type[Any], ClientSession)(read_stream, write_stream)
            if hasattr(session, "__aenter__"):
                await session.__aenter__()
            if hasattr(session, "initialize"):
                await session.initialize()
            try:
                if hasattr(session, "list_tools"):
                    response = await session.list_tools()
                    return getattr(response, "tools", response)
                if hasattr(session, "tools"):
                    return await session.tools()
                raise RuntimeError("MCP session missing list_tools")
            finally:
                if hasattr(session, "__aexit__"):
                    await session.__aexit__(None, None, None)

    return _run_sync(_inner())


def call_tool_from_remote(
    url: str,
    headers: Mapping[str, str],
    tool_name: str,
    arguments: dict[str, Any],
) -> Any:
    """Invoke a tool on a remote MCP server over StreamableHTTP."""
    async def _inner() -> Any:
        ClientSession, streamablehttp_client = _load_remote_sdk()
        async with streamablehttp_client(url, headers=dict(headers)) as (
            read_stream,
            write_stream,
            _get_session_id,
        ):
            session = cast(type[Any], ClientSession)(read_stream, write_stream)
            if hasattr(session, "__aenter__"):
                await session.__aenter__()
            if hasattr(session, "initialize"):
                await session.initialize()
            try:
                return await session.call_tool(name=tool_name, arguments=arguments)
            finally:
                if hasattr(session, "__aexit__"):
                    await session.__aexit__(None, None, None)

    return _run_sync(_inner())

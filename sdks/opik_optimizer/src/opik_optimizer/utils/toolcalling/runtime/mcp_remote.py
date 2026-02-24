"""Remote MCP client helpers using StreamableHTTP transport.

This module uses the MCP Python SDK's streamable HTTP client to connect
to remote MCP servers (see ClientSession + streamablehttp_client). It
mirrors the stdio helpers in ``mcp`` but over HTTP/SSE.
"""

from __future__ import annotations

import asyncio
import json
from typing import Any, TypeVar, cast
from collections.abc import Coroutine, Mapping

from .mcp import ToolCallingDependencyError
from .session_pool import SessionPool
from ... import throttle as _throttle

_T = TypeVar("_T")
_toolcalling_limiter = _throttle.get_toolcalling_rate_limiter()
_POOL = SessionPool()


def _remote_key(
    url: str, headers: Mapping[str, str], auth: Mapping[str, Any] | None
) -> str:
    """Return a stable cache key for remote MCP sessions."""
    payload = {"url": url, "headers": dict(headers), "auth": dict(auth or {})}
    return json.dumps(payload, sort_keys=True, default=str)


async def _get_or_create_client(
    url: str, headers: Mapping[str, str], auth: Mapping[str, Any] | None
) -> tuple[Any, Any]:
    """Return a cached remote client session or create one."""
    key = _remote_key(url, headers, auth)
    return await _POOL.get_or_create(
        key,
        lambda: _start_client(url, headers, auth),
    )


async def _start_client(
    url: str, headers: Mapping[str, str], auth: Mapping[str, Any] | None
) -> tuple[Any, Any]:
    """Start a remote MCP client session and return session + transport."""
    ClientSession, streamablehttp_client = _load_remote_sdk()
    safe_headers = {str(k): "" if v is None else str(v) for k, v in headers.items()}
    transport_kwargs: dict[str, Any] = {"headers": safe_headers}
    if auth is not None:
        transport_kwargs["auth"] = dict(auth)
    try:
        transport_cm = streamablehttp_client(url, **transport_kwargs)
    except TypeError:
        # Older SDK variants may not accept an `auth` argument.
        transport_cm = streamablehttp_client(url, headers=safe_headers)
    session: Any | None = None
    transport_entered = False
    session_entered = False
    try:
        read_stream, write_stream, _get_session_id = await transport_cm.__aenter__()
        transport_entered = True
        session = cast(type[Any], ClientSession)(read_stream, write_stream)
        if hasattr(session, "__aenter__"):
            await session.__aenter__()
            session_entered = True
        if hasattr(session, "initialize"):
            await session.initialize()
        return session, transport_cm
    except Exception:
        if session is not None and session_entered and hasattr(session, "__aexit__"):
            await session.__aexit__(None, None, None)
        if transport_entered:
            await transport_cm.__aexit__(None, None, None)
        raise


async def _close_client(session_bundle: tuple[Any, Any]) -> None:
    """Close a remote MCP client session bundle."""
    session, transport_cm = session_bundle
    if hasattr(session, "__aexit__"):
        await session.__aexit__(None, None, None)
    await transport_cm.__aexit__(None, None, None)


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


def list_tools_from_remote(
    url: str,
    headers: Mapping[str, str],
    auth: Mapping[str, Any] | None = None,
) -> Any:
    """List tools from a remote MCP server over StreamableHTTP."""
    _toolcalling_limiter.acquire()

    async def _inner() -> Any:
        """Async inner to list tools over a remote session."""
        session, _transport = await _get_or_create_client(url, headers, auth)
        if hasattr(session, "list_tools"):
            response = await session.list_tools()
            return getattr(response, "tools", response)
        if hasattr(session, "tools"):
            return await session.tools()
        raise RuntimeError("MCP session missing list_tools")

    return _POOL.run(_inner())


def call_tool_from_remote(
    url: str,
    headers: Mapping[str, str],
    auth: Mapping[str, Any] | None,
    tool_name: str,
    arguments: dict[str, Any],
) -> Any:
    """Invoke a tool on a remote MCP server over StreamableHTTP."""
    _toolcalling_limiter.acquire()

    async def _inner() -> Any:
        """Async inner to call a tool over a remote session."""
        session, _transport = await _get_or_create_client(url, headers, auth)
        return await session.call_tool(name=tool_name, arguments=arguments)

    return _POOL.run(_inner())


_POOL.set_closer(lambda session_bundle: _close_client(session_bundle))

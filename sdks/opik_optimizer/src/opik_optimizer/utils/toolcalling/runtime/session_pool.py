"""Shared helpers for managing persistent toolcalling sessions."""

from __future__ import annotations

import asyncio
import atexit
import threading
import concurrent.futures
from typing import Any, TypeVar
from collections.abc import Callable, Coroutine

_T = TypeVar("_T")


class SessionPool:
    """Run coroutines on a dedicated loop thread and cache sessions."""

    def __init__(self) -> None:
        """Initialize an empty session pool with no event loop."""
        self._lock = threading.Lock()
        self._loop: asyncio.AbstractEventLoop | None = None
        self._thread: threading.Thread | None = None
        self._sessions: dict[str, Any] = {}
        self._closer: Callable[[Any], Coroutine[Any, Any, None]] | None = None
        atexit.register(self.shutdown)

    def _ensure_loop(self) -> asyncio.AbstractEventLoop:
        """Return the shared event loop, creating it if needed."""
        if self._loop is not None:
            return self._loop

        with self._lock:
            if self._loop is not None:
                return self._loop

            loop = asyncio.new_event_loop()

            def _run() -> None:
                """Run the event loop forever in a background thread."""
                asyncio.set_event_loop(loop)
                loop.run_forever()

            thread = threading.Thread(target=_run, daemon=True)
            thread.start()
            self._loop = loop
            self._thread = thread
            return loop

    def run(self, coro: Coroutine[Any, Any, _T]) -> _T:
        """Run a coroutine on the pool loop and return its result."""
        loop = self._ensure_loop()
        future = asyncio.run_coroutine_threadsafe(coro, loop)
        try:
            return future.result(timeout=30)
        except concurrent.futures.CancelledError as exc:  # pragma: no cover - rare
            raise RuntimeError("SessionPool task was cancelled") from exc
        except concurrent.futures.TimeoutError as exc:  # pragma: no cover - rare
            raise RuntimeError("SessionPool task timed out") from exc

    async def get_or_create(
        self,
        key: str,
        factory: Callable[[], Coroutine[Any, Any, Any]],
    ) -> Any:
        """Return an existing session or create it with the factory."""
        if key in self._sessions:
            return self._sessions[key]
        session = await factory()
        self._sessions[key] = session
        return session

    async def close_all(
        self, closer: Callable[[Any], Coroutine[Any, Any, None]]
    ) -> None:
        """Close all cached sessions using the provided closer."""
        sessions = list(self._sessions.values())
        self._sessions.clear()
        for session in sessions:
            await closer(session)

    def set_closer(self, closer: Callable[[Any], Coroutine[Any, Any, None]]) -> None:
        """Register a closer coroutine used during shutdown."""
        self._closer = closer

    def shutdown(self) -> None:
        """Shut down the pool and close all sessions."""
        loop = self._loop
        if loop is None:
            return
        try:
            future = asyncio.run_coroutine_threadsafe(self._shutdown_async(), loop)
            future.result(timeout=5)
        except Exception:
            pass
        loop.call_soon_threadsafe(loop.stop)

    async def _shutdown_async(self) -> None:
        """Async helper to close sessions during shutdown."""
        if self._closer is None:
            return None
        await self.close_all(self._closer)
        return None

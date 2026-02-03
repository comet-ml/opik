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
        self._lock = threading.Lock()
        self._loop: asyncio.AbstractEventLoop | None = None
        self._thread: threading.Thread | None = None
        self._sessions: dict[str, Any] = {}
        self._closer: Callable[[Any], Coroutine[Any, Any, None]] | None = None
        atexit.register(self.shutdown)

    def _ensure_loop(self) -> asyncio.AbstractEventLoop:
        if self._loop is not None:
            return self._loop

        with self._lock:
            if self._loop is not None:
                return self._loop

            loop = asyncio.new_event_loop()

            def _run() -> None:
                asyncio.set_event_loop(loop)
                loop.run_forever()

            thread = threading.Thread(target=_run, daemon=True)
            thread.start()
            self._loop = loop
            self._thread = thread
            return loop

    def run(self, coro: Coroutine[Any, Any, _T]) -> _T:
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
        if key in self._sessions:
            return self._sessions[key]
        session = await factory()
        self._sessions[key] = session
        return session

    async def close_all(
        self, closer: Callable[[Any], Coroutine[Any, Any, None]]
    ) -> None:
        sessions = list(self._sessions.values())
        self._sessions.clear()
        for session in sessions:
            await closer(session)

    def set_closer(self, closer: Callable[[Any], Coroutine[Any, Any, None]]) -> None:
        self._closer = closer

    def shutdown(self) -> None:
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
        if self._closer is None:
            return None
        await self.close_all(self._closer)
        return None

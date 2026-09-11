"""Shared helpers for benchmark e2e tests."""

from __future__ import annotations

from concurrent.futures import Future
from typing import Any
from collections.abc import Callable


class InlineExecutor:
    """Synchronous stand-in for ProcessPoolExecutor used in benchmark e2e tests."""

    def __init__(self, *args: Any, **kwargs: Any) -> None:
        self.submissions: list[tuple] = []

    def __enter__(self) -> InlineExecutor:  # pragma: no cover - trivial
        return self

    def __exit__(self, exc_type: Any, exc: Any, _tb: Any) -> None:  # pragma: no cover
        _ = exc_type, exc
        return None

    def submit(self, fn: Callable[..., Any], *args: Any, **kwargs: Any) -> Future[Any]:
        result = fn(*args, **kwargs)
        self.submissions.append((fn, args, kwargs, result))
        fut: Future[Any] = Future()
        fut.set_result(result)
        return fut

    def shutdown(self, wait: bool = True, _cancel_futures: bool = False) -> None:
        _ = wait, _cancel_futures
        return None

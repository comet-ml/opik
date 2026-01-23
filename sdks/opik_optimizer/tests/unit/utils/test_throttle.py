from __future__ import annotations

from typing import Any

import pytest

from opik_optimizer.utils import throttle


class DummyLimiter(throttle.RateLimiter):
    def __init__(self) -> None:
        # Avoid initializing the base limiter (pyrate dependency) since tests mock behavior.
        self.acquire_called = False
        self.acquire_async_called = False

    def acquire(self) -> None:
        self.acquire_called = True

    async def acquire_async(self) -> None:
        self.acquire_async_called = True


def test_rate_limited_decorator_invokes_acquire() -> None:
    limiter = DummyLimiter()
    called: list[str] = []

    @throttle.rate_limited(limiter)
    def wrapped(value: str) -> str:
        called.append(value)
        return value

    result = wrapped("ok")

    assert result == "ok"
    assert called == ["ok"]
    assert limiter.acquire_called


@pytest.mark.asyncio
async def test_rate_limited_async_decorator_invokes_acquire_async() -> None:
    limiter = DummyLimiter()
    called: list[str] = []

    @throttle.rate_limited_async(limiter)
    async def wrapped(value: str) -> str:
        called.append(value)
        return value

    result = await wrapped("async-ok")

    assert result == "async-ok"
    assert called == ["async-ok"]
    assert limiter.acquire_async_called


def test_rate_limiter_falls_back_when_argument_removed(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: dict[str, Any] = {}

    class DummyLimiter:
        def __init__(self, rate: Any, **kwargs: Any) -> None:
            if "raise_when_fail" in kwargs:
                captured["raised"] = True
                raise TypeError("unsupported argument")
            captured["rate"] = rate

        def try_acquire(self, key: Any, *, blocking: bool = True) -> bool:  # noqa: ARG002
            return True

    monkeypatch.setattr(
        "opik_optimizer.utils.throttle.pyrate_limiter.Limiter",
        DummyLimiter,
    )

    limiter = throttle.RateLimiter(max_calls_per_second=5)
    assert captured["raised"] is True
    assert limiter.max_calls_per_second == 5

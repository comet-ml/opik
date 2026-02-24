import asyncio
import functools
import inspect
import logging
import time
from collections.abc import Awaitable, Callable
from typing import Any, ParamSpec, TypeVar

import opik.config
import pyrate_limiter

logger = logging.getLogger(__name__)

P = ParamSpec("P")
R = TypeVar("R")


class RateLimiter:
    """
    Rate limiter that enforces a maximum number of calls across all threads using pyrate_limiter.
    """

    def __init__(self, max_calls_per_second: int):
        self.max_calls_per_second = max_calls_per_second
        rate = pyrate_limiter.Rate(max_calls_per_second, pyrate_limiter.Duration.SECOND)
        limiter_cls: Any = pyrate_limiter.Limiter
        launcher_kwargs: dict[str, Any] = {}
        init_sig = inspect.signature(limiter_cls.__init__)
        if "raise_when_fail" in init_sig.parameters:
            launcher_kwargs["raise_when_fail"] = False
        self.limiter = limiter_cls(rate, **launcher_kwargs)
        self.bucket_key = "global_rate_limit"
        self._sync_try_acquire: Callable[[], bool] | None = None
        self._async_try_acquire: Callable[[], Awaitable[bool]] | None = None
        self._blocking_acquire: Callable[[], bool] = lambda: self.limiter.try_acquire(
            self.bucket_key
        )

        try:
            sig = inspect.signature(self.limiter.try_acquire)
        except (ValueError, TypeError):
            sig = None
        if sig and "blocking" in sig.parameters:

            def _nonblocking_try() -> bool:
                return self.limiter.try_acquire(self.bucket_key, blocking=False)

            self._sync_try_acquire = _nonblocking_try

        async_acquire_attr = getattr(self.limiter, "try_acquire_async", None)
        if async_acquire_attr is not None:
            try:
                async_sig = inspect.signature(async_acquire_attr)
            except (TypeError, ValueError):
                async_sig = None
            if async_sig and "blocking" in async_sig.parameters:
                self._async_try_acquire = lambda: async_acquire_attr(
                    self.bucket_key, blocking=False
                )
            else:
                self._async_try_acquire = lambda: async_acquire_attr(self.bucket_key)

        if self._sync_try_acquire is None and self._async_try_acquire is None:
            logger.warning(
                "pyrate_limiter.Limiter does not expose non-blocking acquire; falling back to blocking behavior."
            )

    def acquire(self) -> None:
        if self._sync_try_acquire is not None:
            while not self._sync_try_acquire():
                time.sleep(0.01)
            return
        while not self._blocking_acquire():
            time.sleep(0.01)

    async def acquire_async(self) -> None:
        if self._async_try_acquire is not None:
            while not await self._async_try_acquire():
                await asyncio.sleep(0.01)
            return
        if self._sync_try_acquire is not None:
            while not self._sync_try_acquire():
                await asyncio.sleep(0.01)
            return
        while not self._blocking_acquire():
            await asyncio.sleep(0.01)


def rate_limited(limiter: RateLimiter) -> Callable[[Callable[P, R]], Callable[P, R]]:
    """Decorator to rate limit a function using the provided limiter"""

    def decorator(func: Callable[P, R]) -> Callable[P, R]:
        @functools.wraps(func)
        def wrapper(*args: P.args, **kwargs: P.kwargs) -> R:
            limiter.acquire()
            return func(*args, **kwargs)

        return wrapper

    return decorator


def rate_limited_async(
    limiter: RateLimiter,
) -> Callable[[Callable[P, Awaitable[R]]], Callable[P, Awaitable[R]]]:
    """Decorator to rate limit async functions without blocking the loop"""

    def decorator(func: Callable[P, Awaitable[R]]) -> Callable[P, Awaitable[R]]:
        @functools.wraps(func)
        async def wrapper(*args: P.args, **kwargs: P.kwargs) -> R:
            await limiter.acquire_async()
            return await func(*args, **kwargs)

        return wrapper

    return decorator


def get_rate_limiter_for_current_opik_installation() -> RateLimiter:
    """Get the rate limiter for the current Opik installation."""
    opik_config = opik.config.OpikConfig()
    max_calls_per_second = 10 if opik_config.is_cloud_installation else 50
    return RateLimiter(max_calls_per_second=max_calls_per_second)


def get_toolcalling_rate_limiter() -> RateLimiter:
    """Get a rate limiter for toolcalling operations."""
    return get_rate_limiter_for_current_opik_installation()

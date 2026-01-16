"""Backwards-compatible throttle module."""

from .utils.throttle import (  # noqa: F401
    get_rate_limiter_for_current_opik_installation,
    rate_limited,
    rate_limited_async,
)

__all__ = [
    "get_rate_limiter_for_current_opik_installation",
    "rate_limited",
    "rate_limited_async",
]

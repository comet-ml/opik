"""Pytest fixtures for disabling throttling/rate limiting."""

from __future__ import annotations

from collections.abc import Callable

import pytest


@pytest.fixture
def disable_rate_limiting(monkeypatch: pytest.MonkeyPatch) -> None:
    """
    Disable rate limiting for fast test execution.

    Replaces the rate limiting decorators with no-ops so tests
    don't have artificial delays.
    """

    def passthrough_decorator(func: Callable[..., object]) -> Callable[..., object]:
        return func

    def passthrough_factory() -> Callable[
        [Callable[..., object]], Callable[..., object]
    ]:
        return passthrough_decorator

    monkeypatch.setattr(
        "opik_optimizer.utils.throttle.rate_limited", passthrough_factory
    )
    monkeypatch.setattr(
        "opik_optimizer.utils.throttle.rate_limited_async", passthrough_factory
    )

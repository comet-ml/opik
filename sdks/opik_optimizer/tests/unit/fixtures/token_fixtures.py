"""Pytest fixtures for token counting mocks."""

from __future__ import annotations

from collections.abc import Callable
from typing import Any

import pytest


@pytest.fixture
def mock_token_counter(
    monkeypatch: pytest.MonkeyPatch,
) -> Callable[..., Callable[..., int]]:
    """
    Mock LiteLLM token counting for predictable tests.

    Usage:
        mock_token_counter(100)
        mock_token_counter(side_effect=lambda **kw: ...)
    """

    def _configure(
        token_count: int | None = None,
        *,
        side_effect: Callable[..., int] | None = None,
    ) -> Callable[..., int]:
        def fake_counter(**kwargs: Any) -> int:
            if side_effect is not None:
                return side_effect(**kwargs)
            return token_count or 100

        monkeypatch.setattr("litellm.token_counter", fake_counter)
        return fake_counter

    return _configure

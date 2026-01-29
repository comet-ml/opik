"""Pytest fixtures for LLM call mocking used across unit tests."""

from __future__ import annotations

from collections.abc import Awaitable, Callable
from typing import Any

import pytest


MockedCallModel = Callable[..., Any]
MockedAsyncCallModel = Callable[..., Awaitable[Any]]


@pytest.fixture
def mock_llm_call(monkeypatch: pytest.MonkeyPatch) -> Callable[..., MockedCallModel]:
    """
    Factory fixture for mocking synchronous LLM calls.

    Intercepts calls to `opik_optimizer.core.llm_calls.call_model()` and returns
    the configured response.
    """

    def _configure(
        response: Any = None,
        *,
        raises: Exception | None = None,
        side_effect: Callable[..., Any] | None = None,
    ) -> MockedCallModel:
        captured_calls: list[dict[str, Any]] = []

        def fake_call_model(**kwargs: Any) -> Any:
            captured_calls.append(kwargs)
            if raises:
                raise raises
            if side_effect:
                return side_effect(**kwargs)
            return response

        monkeypatch.setattr("opik_optimizer.core.llm_calls.call_model", fake_call_model)
        fake_call_model.calls = captured_calls  # type: ignore[attr-defined]
        return fake_call_model

    return _configure


@pytest.fixture
def mock_llm_call_async(
    monkeypatch: pytest.MonkeyPatch,
) -> Callable[..., MockedAsyncCallModel]:
    """
    Factory fixture for mocking asynchronous LLM calls.

    Intercepts calls to `opik_optimizer.core.llm_calls.call_model_async()`.
    """

    def _configure(
        response: Any = None,
        *,
        raises: Exception | None = None,
        side_effect: Callable[..., Any] | None = None,
    ) -> MockedAsyncCallModel:
        captured_calls: list[dict[str, Any]] = []

        async def fake_call_model_async(**kwargs: Any) -> Any:
            captured_calls.append(kwargs)
            if raises:
                raise raises
            if side_effect:
                if callable(side_effect):
                    result = side_effect(**kwargs)
                    # Handle both sync and async side effects
                    if hasattr(result, "__await__"):
                        return await result
                    return result
                return side_effect
            return response

        monkeypatch.setattr(
            "opik_optimizer.core.llm_calls.call_model_async", fake_call_model_async
        )
        fake_call_model_async.calls = captured_calls  # type: ignore[attr-defined]
        return fake_call_model_async

    return _configure

"""Pytest fixtures for LLM call mocking used across unit tests."""

from __future__ import annotations

from typing import Any

import pytest


@pytest.fixture
def mock_llm_call(monkeypatch: pytest.MonkeyPatch):
    """
    Factory fixture for mocking synchronous LLM calls.

    Intercepts calls to `opik_optimizer.core.llm_calls.call_model()` and returns
    the configured response.
    """

    def _configure(
        response: Any = None,
        *,
        raises: Exception | None = None,
        side_effect: Any | None = None,
    ):
        captured_calls: list[dict[str, Any]] = []

        def fake_call_model(**kwargs):
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
def mock_llm_call_async(monkeypatch: pytest.MonkeyPatch):
    """
    Factory fixture for mocking asynchronous LLM calls.

    Intercepts calls to `opik_optimizer.core.llm_calls.call_model_async()`.
    """

    def _configure(
        response: Any = None,
        *,
        raises: Exception | None = None,
        side_effect: Any | None = None,
    ):
        captured_calls: list[dict[str, Any]] = []

        async def fake_call_model_async(**kwargs):
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


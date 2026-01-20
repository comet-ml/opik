"""Pytest fixtures for sequenced LLM call mocking."""

from __future__ import annotations

from typing import Any

import pytest


@pytest.fixture
def mock_llm_sequence(monkeypatch: pytest.MonkeyPatch):
    """
    Mock LLM to return different responses on successive calls.

    Intercepts `opik_optimizer.core.llm_calls.call_model`.
    """

    def _configure(responses: list[Any]):
        call_count: dict[str, Any] = {"n": 0}
        captured_calls: list[dict[str, Any]] = []

        def fake_call_model(**kwargs):
            captured_calls.append(kwargs)
            idx = min(call_count["n"], len(responses) - 1)
            call_count["n"] += 1
            result = responses[idx]
            if isinstance(result, Exception):
                raise result
            return result

        monkeypatch.setattr("opik_optimizer.core.llm_calls.call_model", fake_call_model)
        call_count["calls"] = captured_calls
        return call_count

    return _configure


@pytest.fixture
def mock_llm_sequence_async(monkeypatch: pytest.MonkeyPatch):
    """Async version of mock_llm_sequence (for call_model_async)."""

    def _configure(responses: list[Any]):
        call_count: dict[str, Any] = {"n": 0}
        captured_calls: list[dict[str, Any]] = []

        async def fake_call_model_async(**kwargs):
            captured_calls.append(kwargs)
            idx = min(call_count["n"], len(responses) - 1)
            call_count["n"] += 1
            result = responses[idx]
            if isinstance(result, Exception):
                raise result
            return result

        monkeypatch.setattr(
            "opik_optimizer.core.llm_calls.call_model_async", fake_call_model_async
        )
        call_count["calls"] = captured_calls
        return call_count

    return _configure


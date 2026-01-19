"""Global pytest configuration for e2e optimizer tests."""

from __future__ import annotations

import pytest


@pytest.fixture(autouse=True)
def _limit_tool_call_iterations(monkeypatch: pytest.MonkeyPatch) -> None:
    """Limit tool-call loops for e2e runs without affecting other test suites."""
    monkeypatch.setenv("OPIK_OPTIMIZER_TOOL_CALL_MAX_ITERATIONS", "1")

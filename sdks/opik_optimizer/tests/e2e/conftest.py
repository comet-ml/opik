"""Global pytest configuration for e2e optimizer tests."""

from __future__ import annotations

from typing import Any
from collections.abc import Generator

import pytest


_E2E_PROJECT_NAME = "e2e-optimizer-tests"


@pytest.fixture(scope="session")
def setup_environment() -> Generator[None, Any, None]:
    """Setup environment for e2e optimizer tests."""
    with pytest.MonkeyPatch.context() as m:
        m.setenv("OPIK_PROJECT_NAME", _E2E_PROJECT_NAME)
        yield


@pytest.fixture(autouse=True)
def _limit_tool_call_iterations(monkeypatch: pytest.MonkeyPatch) -> None:
    """Limit tool-call loops for e2e runs without affecting other test suites."""
    monkeypatch.setenv("OPIK_TOOL_CALL_MAX_ITERATIONS", "1")

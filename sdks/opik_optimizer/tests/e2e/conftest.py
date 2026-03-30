"""Global pytest configuration for e2e optimizer tests."""

from __future__ import annotations

import uuid
from typing import Any, Generator

import pytest


@pytest.fixture(scope="session")
def setup_environment() -> Generator[None, Any, None]:
    """Setup environment for e2e optimizer tests."""
    _E2E_PROJECT_NAME = f"e2e-optimizer-tests-{uuid.uuid4().hex[:8]}"

    with pytest.MonkeyPatch.context() as m:
        m.setenv("OPIK_PROJECT_NAME", _E2E_PROJECT_NAME)
        yield


@pytest.fixture(autouse=True)
def _limit_tool_call_iterations(monkeypatch: pytest.MonkeyPatch) -> None:
    """Limit tool-call loops for e2e runs without affecting other test suites."""
    monkeypatch.setenv("OPIK_TOOL_CALL_MAX_ITERATIONS", "1")

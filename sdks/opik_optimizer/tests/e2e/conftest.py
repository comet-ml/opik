"""Global pytest configuration for e2e optimizer tests."""

from __future__ import annotations

import os
import uuid

import pytest

_E2E_PROJECT_NAME = f"e2e-optimizer-tests-{uuid.uuid4().hex[:8]}"


@pytest.fixture(autouse=True, scope="session")
def setup_environment() -> None:
    """Setup environment for e2e optimizer tests."""
    os.environ["OPIK_PROJECT_NAME"] = _E2E_PROJECT_NAME

@pytest.fixture(autouse=True)
def _limit_tool_call_iterations(monkeypatch: pytest.MonkeyPatch) -> None:
    """Limit tool-call loops for e2e runs without affecting other test suites."""
    monkeypatch.setenv("OPIK_TOOL_CALL_MAX_ITERATIONS", "1")

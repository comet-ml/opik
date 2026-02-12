"""Pytest fixtures for pytest integration E2E demo output."""

from __future__ import annotations

import pytest

from demo_helpers import setup_demo_logging


@pytest.fixture(scope="session", autouse=True)
def _configure_demo_logging() -> None:
    """Apply demo logging once for all tests in this folder."""
    setup_demo_logging()

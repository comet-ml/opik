"""Pytest fixtures for disabling noisy display output during unit tests."""

from __future__ import annotations

import os

import pytest

from opik_optimizer.constants import OPIK_OPTIMIZER_NO_BANNER_ENV


os.environ.setdefault(OPIK_OPTIMIZER_NO_BANNER_ENV, "1")


@pytest.fixture(autouse=True)
def _disable_display_output(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv(OPIK_OPTIMIZER_NO_BANNER_ENV, "1")

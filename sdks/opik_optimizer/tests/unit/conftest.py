# mypy: disable-error-code=no-untyped-def

"""
Unit test fixtures are provided via plugin modules under `tests/unit/fixtures/`.

They are registered from the package-level `sdks/opik_optimizer/conftest.py`
to satisfy modern pytest restrictions around `pytest_plugins`.
"""

import os

import pytest
from opik.api_objects import opik_client


@pytest.fixture(autouse=True)
def no_opik_backend(monkeypatch):
    """Keep unit tests off any real Opik workspace, even with credentials set.

    Clients created in the test point at a closed local port, so optimization
    records and traces go nowhere; the cached client is dropped afterwards so
    later e2e tests in the same session get a real one.
    """
    for name in ("OPIK_API_KEY", "OPIK_WORKSPACE", "OPIK_PROJECT_NAME"):
        monkeypatch.delenv(name, raising=False)
    monkeypatch.setenv("OPIK_URL_OVERRIDE", "http://127.0.0.1:9/api")
    monkeypatch.setenv("OPIK_CONFIG_PATH", os.devnull)
    opik_client.reset_global_client(end_client=False)
    yield
    opik_client.reset_global_client(end_client=False)

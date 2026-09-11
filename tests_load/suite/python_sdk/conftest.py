"""Pytest fixtures for the Python SDK load-test suite."""

import logging
import os
from typing import Iterator

import pytest
from opik import context_storage

from ._helpers import Metrics


logging.basicConfig(
    level=logging.INFO,
    format="%(levelname)s [%(asctime)s] %(name)s: %(message)s",
)


def pytest_addoption(parser: pytest.Parser) -> None:
    parser.addoption(
        "--load-scale",
        type=float,
        default=float(os.getenv("OPIK_LOAD_SCALE", "1.0")),
        help="Multiplier applied to default trace/span counts in load tests.",
    )


@pytest.fixture(autouse=True)
def _reset_opik_context_after_test() -> Iterator[None]:
    """Clears SDK context between tests so leaks don't cross test boundaries.

    The ``start_as_current_trace`` / ``start_as_current_span`` context
    managers acquire ``context_storage`` project-name ownership on enter
    but don't release it on exit; if the next test uses ``@opik.track``
    with a new project, traces silently land in the leaked project. This
    fixture neutralises that across our suite.
    """
    yield
    context_storage.clear_all()


@pytest.fixture
def metrics(request: pytest.FixtureRequest) -> Iterator[Metrics]:
    recorder = Metrics(test_name=request.node.name)
    yield recorder
    recorder.write()


@pytest.fixture
def load_scale(request: pytest.FixtureRequest) -> float:
    return float(request.config.getoption("--load-scale"))

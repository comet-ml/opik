"""
Pytest fixtures for multimodal e2e tests.

This module ensures the driving_hazard dataset is created once before
any tests run, allowing parallel test execution.
"""
from typing import Any, Generator

import pytest

import opik
import opik_optimizer
from opik import Dataset


@pytest.fixture(scope="session")
def setup_driving_hazard_dataset(setup_environment) -> Generator[Dataset, Any, None]:
    """
    Create the driving_hazard dataset before any tests run.

    This fixture runs once per session and ensures the dataset exists
    before parallel tests try to access it, avoiding race conditions.
    """
    # Create the dataset (will be reused by all tests)
    dataset = opik_optimizer.datasets.driving_hazard(test_mode=True, count=1)
    yield dataset
    # Cleanup is optional - leave dataset for future runs

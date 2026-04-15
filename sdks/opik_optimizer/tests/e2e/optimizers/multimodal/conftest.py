"""
Pytest fixtures for multimodal e2e tests.

This module ensures the driving_hazard dataset is created once before
any tests run, allowing parallel test execution.
"""

from typing import Any
from collections.abc import Generator

import pytest

import opik_optimizer
from opik import Dataset

from ..utils import dataset_helpers


@pytest.fixture(scope="session")
def setup_driving_hazard_dataset(setup_environment) -> Generator[Dataset, Any, None]:  # type: ignore
    """
    Create the driving_hazard dataset before any tests run.

    This fixture runs once per session and ensures the dataset exists
    before parallel tests try to access it, avoiding race conditions.
    """
    dataset_helpers.remove_old_datasets(["driving_hazard_train_1_sample"])
    # Create the dataset (will be reused by all tests)
    dataset = opik_optimizer.datasets.driving_hazard(test_mode=True, count=1)
    yield dataset
    # Cleanup is optional - leave dataset for future runs

import pytest

import opik
import opik_optimizer  # type: ignore[import-not-found]


@pytest.fixture(scope="session", autouse=True)
def setup_tiny_test_dataset() -> opik.Dataset:
    dataset = opik_optimizer.datasets.tiny_test()
    yield dataset

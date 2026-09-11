import pytest

from opik.runner import registry


@pytest.fixture(autouse=True)
def clear_registry():
    registry.REGISTRY.clear()
    yield
    registry.REGISTRY.clear()

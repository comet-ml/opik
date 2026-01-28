"""Pytest fixtures for Opik platform mocking used by unit tests."""

from __future__ import annotations

from collections.abc import Callable
from typing import Any
from unittest.mock import MagicMock

import pytest

from tests.unit.fixtures.builders import make_mock_dataset


@pytest.fixture
def mock_opik_client(monkeypatch: pytest.MonkeyPatch) -> Callable[..., MagicMock]:
    """
    Mock Opik client to avoid network calls during tests.
    """

    def _configure(
        *,
        optimization_id: str = "opt-123",
        dataset_id: str = "ds-123",
    ) -> MagicMock:
        mock_client = MagicMock()

        # Create optimization mock
        mock_optimization = MagicMock()
        mock_optimization.id = optimization_id
        mock_optimization.update = MagicMock()
        mock_client.create_optimization.return_value = mock_optimization

        # Dataset mock
        mock_dataset = MagicMock()
        mock_dataset.id = dataset_id
        mock_client.get_dataset_by_name.return_value = mock_dataset

        # Patch the Opik class
        monkeypatch.setattr("opik.Opik", lambda **_kw: mock_client)

        return mock_client

    return _configure


@pytest.fixture
def mock_dataset() -> Callable[..., MagicMock]:
    """
    Factory for creating mock Dataset objects.
    """

    def _create(
        items: list[dict[str, Any]],
        *,
        name: str = "test-dataset",
        dataset_id: str = "dataset-123",
    ) -> MagicMock:
        return make_mock_dataset(items, name=name, dataset_id=dataset_id)

    return _create

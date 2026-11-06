"""Unit tests for BaseOptimizer misc framework helpers (init + utility methods)."""

# mypy: disable-error-code=no-untyped-def

from __future__ import annotations

from typing import Any
from unittest.mock import MagicMock

import pytest

from tests.unit.fixtures.base_optimizer_test_helpers import ConcreteOptimizer
from tests.unit.test_helpers import make_mock_dataset


class TestCreateOptimizationRun:
    @pytest.fixture
    def optimizer(self) -> ConcreteOptimizer:
        return ConcreteOptimizer(model="gpt-4")

    def test_creates_optimization_and_sets_id(
        self, optimizer: ConcreteOptimizer, mock_opik_client
    ) -> None:
        mock_client = mock_opik_client()
        mock_client.create_optimization.return_value = MagicMock(id="opt-123")

        mock_ds = make_mock_dataset(name="test-dataset")

        def metric(dataset_item: Any, llm_output: Any) -> float:
            _ = dataset_item, llm_output
            return 1.0

        result = optimizer._create_optimization_run(mock_ds, metric)

        assert result is not None
        assert optimizer.current_optimization_id == "opt-123"
        _, call_kwargs = mock_client.create_optimization.call_args
        assert call_kwargs.get("project_name") == optimizer.project_name

    def test_returns_none_on_error(
        self, optimizer: ConcreteOptimizer, mock_opik_client
    ) -> None:
        mock_client = mock_opik_client()
        mock_client.create_optimization.side_effect = Exception("API error")

        mock_ds = make_mock_dataset(name="test-dataset")

        def metric(dataset_item: Any, llm_output: Any) -> float:
            _ = dataset_item, llm_output
            return 1.0

        result = optimizer._create_optimization_run(mock_ds, metric)

        assert result is None
        assert optimizer.current_optimization_id is None

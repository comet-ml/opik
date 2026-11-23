"""Unit tests for BaseOptimizer._setup_optimization: tracking + params plumbing."""

# mypy: disable-error-code=no-untyped-def

from __future__ import annotations

from typing import Any
from unittest.mock import MagicMock

import pytest

from opik_optimizer.api_objects.types import MetricFunction
from tests.unit.fixtures.base_optimizer_test_helpers import ConcreteOptimizer
from tests.unit.test_helpers import make_mock_dataset


@pytest.fixture
def optimizer() -> ConcreteOptimizer:
    return ConcreteOptimizer(model="gpt-4")


@pytest.fixture
def mock_metric() -> MetricFunction:
    def metric(dataset_item: dict[str, Any], llm_output: str) -> float:
        _ = dataset_item, llm_output
        return 1.0

    metric.__name__ = "test_metric"
    return metric


class TestSetupOptimizationParamsAndTracking:
    def test_optimization_id_fetches_existing_optimization(
        self,
        optimizer: ConcreteOptimizer,
        simple_chat_prompt,
        mock_opik_client,
        mock_metric: MetricFunction,
    ) -> None:
        """optimization_id should fetch and set current_optimization_id and context.optimization_id."""
        mock_client = mock_opik_client()
        mock_ds = make_mock_dataset(
            [{"id": "1", "input": "test"}],
            name="test-dataset",
            dataset_id="ds-123",
        )

        existing = MagicMock()
        existing.id = "opt-999"
        mock_client.get_optimization_by_id = MagicMock(return_value=existing)

        context = optimizer._setup_optimization(
            prompt=simple_chat_prompt,
            dataset=mock_ds,
            metric=mock_metric,
            compute_baseline=False,
            optimization_id="opt-999",
        )

        assert optimizer.current_optimization_id == "opt-999"
        assert context.optimization_id == "opt-999"
        assert context.optimization is existing

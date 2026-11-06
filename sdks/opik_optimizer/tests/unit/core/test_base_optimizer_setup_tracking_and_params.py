"""Unit tests for BaseOptimizer._setup_optimization: tracking + params plumbing."""

# mypy: disable-error-code=no-untyped-def

from __future__ import annotations

from typing import Any
from unittest.mock import MagicMock

import pytest
from opik.rest_api.core import ApiError

from opik_optimizer import MetaPromptOptimizer
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
        existing.id = "01900000-0000-7000-8000-000000000001"
        mock_client.get_optimization_by_id = MagicMock(return_value=existing)

        context = optimizer._setup_optimization(
            prompt=simple_chat_prompt,
            dataset=mock_ds,
            metric=mock_metric,
            compute_baseline=False,
            optimization_id="01900000-0000-7000-8000-000000000001",
        )

        assert (
            optimizer.current_optimization_id == "01900000-0000-7000-8000-000000000001"
        )
        assert context.optimization_id == "01900000-0000-7000-8000-000000000001"
        assert context.optimization is existing
        mock_client.create_optimization.assert_not_called()

    def test_missing_optimization_id_creates_run_with_that_id(
        self,
        optimizer: ConcreteOptimizer,
        simple_chat_prompt,
        mock_opik_client,
        mock_metric: MetricFunction,
    ) -> None:
        """An optimization_id the backend doesn't know is created with that ID."""
        mock_client = mock_opik_client(
            optimization_id="01900000-0000-7000-8000-000000000002"
        )
        mock_client.get_optimization_by_id.side_effect = ApiError(
            status_code=404, body="not found"
        )

        context = optimizer._setup_optimization(
            prompt=simple_chat_prompt,
            dataset=make_mock_dataset([{"id": "1", "input": "test"}]),
            metric=mock_metric,
            optimization_id="01900000-0000-7000-8000-000000000002",
        )

        create_kwargs = mock_client.create_optimization.call_args.kwargs
        assert (
            create_kwargs["optimization_id"] == "01900000-0000-7000-8000-000000000002"
        )
        assert context.optimization_id == "01900000-0000-7000-8000-000000000002"
        assert context.optimization is mock_client.create_optimization.return_value

    def test_optimization_lookup_errors_other_than_not_found_propagate(
        self,
        optimizer: ConcreteOptimizer,
        simple_chat_prompt,
        mock_opik_client,
        mock_metric: MetricFunction,
    ) -> None:
        mock_client = mock_opik_client()
        mock_client.get_optimization_by_id.side_effect = ApiError(
            status_code=500, body="boom"
        )
        with pytest.raises(ApiError):
            optimizer._setup_optimization(
                prompt=simple_chat_prompt,
                dataset=make_mock_dataset([{"id": "1", "input": "test"}]),
                metric=mock_metric,
                optimization_id="01900000-0000-7000-8000-000000000003",
            )
        mock_client.create_optimization.assert_not_called()


@pytest.mark.parametrize(
    "optimization_id", ["", "not-a-uuid", "550e8400-e29b-41d4-a716-446655440000"]
)
def test_invalid_requested_optimization_id_fails_before_tracking_calls(
    simple_chat_prompt,
    mock_opik_client,
    mock_metric,
    monkeypatch,
    optimization_id,
):
    optimizer = MetaPromptOptimizer(model="optimizer-model", verbose=0)
    client = mock_opik_client()
    baseline = MagicMock(return_value=1.0)
    monkeypatch.setattr(optimizer, "_calculate_baseline", baseline)
    with pytest.raises(ValueError, match="optimization_id.*UUIDv7"):
        optimizer.optimize_prompt(
            prompt=simple_chat_prompt,
            dataset=make_mock_dataset([{"id": "1", "input": "test"}]),
            metric=mock_metric,
            optimization_id=optimization_id,
            max_trials=1,
        )
    client.get_optimization_by_id.assert_not_called()
    client.create_optimization.assert_not_called()
    baseline.assert_not_called()


@pytest.mark.parametrize("status", [400, 503])
def test_requested_optimization_creation_failure_propagates(
    simple_chat_prompt,
    mock_opik_client,
    mock_metric,
    monkeypatch,
    status,
):
    optimizer = MetaPromptOptimizer(model="optimizer-model", verbose=0)
    optimization_id = "01900000-0000-7000-8000-000000000004"
    client = mock_opik_client()
    client.get_optimization_by_id.side_effect = ApiError(
        status_code=404, body="missing"
    )
    client.create_optimization.side_effect = ApiError(
        status_code=status, body="creation rejected"
    )
    baseline = MagicMock(return_value=1.0)
    monkeypatch.setattr(optimizer, "_calculate_baseline", baseline)
    with pytest.raises(ApiError, match="creation rejected"):
        optimizer.optimize_prompt(
            prompt=simple_chat_prompt,
            dataset=make_mock_dataset([{"id": "1", "input": "test"}]),
            metric=mock_metric,
            optimization_id=optimization_id,
            max_trials=1,
        )
    assert optimizer.current_optimization_id is None
    baseline.assert_not_called()

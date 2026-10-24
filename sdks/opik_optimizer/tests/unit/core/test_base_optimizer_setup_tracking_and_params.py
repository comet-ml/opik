"""Unit tests for BaseOptimizer._setup_optimization: tracking + params plumbing."""

# mypy: disable-error-code=no-untyped-def

from __future__ import annotations

from typing import Any
from unittest.mock import MagicMock

import pytest
from opik.rest_api.core import ApiError

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
    def test_stores_extra_params(
        self,
        optimizer: ConcreteOptimizer,
        simple_chat_prompt,
        mock_opik_client,
        mock_metric: MetricFunction,
    ) -> None:
        """Extra kwargs should be stored in context.extra_params."""
        mock_opik_client()
        mock_ds = make_mock_dataset(
            [{"id": "1", "input": "test"}],
            name="test-dataset",
            dataset_id="ds-123",
        )

        context = optimizer._setup_optimization(
            prompt=simple_chat_prompt,
            dataset=mock_ds,
            metric=mock_metric,
            compute_baseline=False,
            custom_param="custom_value",
            another_param=42,
        )

        assert context.extra_params["custom_param"] == "custom_value"
        assert context.extra_params["another_param"] == 42

    def test_stores_auto_continue_in_extra_params(
        self,
        optimizer: ConcreteOptimizer,
        simple_chat_prompt,
        mock_opik_client,
        mock_metric: MetricFunction,
    ) -> None:
        """auto_continue should be stored in context.extra_params."""
        mock_opik_client()
        mock_ds = make_mock_dataset(
            [{"id": "1", "input": "test"}],
            name="test-dataset",
            dataset_id="ds-123",
        )

        context = optimizer._setup_optimization(
            prompt=simple_chat_prompt,
            dataset=mock_ds,
            metric=mock_metric,
            compute_baseline=False,
            auto_continue=True,
        )

        assert context.extra_params.get("auto_continue") is True

    def test_stores_experiment_config_on_context(
        self,
        optimizer: ConcreteOptimizer,
        simple_chat_prompt,
        mock_opik_client,
        mock_metric: MetricFunction,
    ) -> None:
        """experiment_config should be stored on the returned context."""
        mock_opik_client()
        mock_ds = make_mock_dataset(
            [{"id": "1", "input": "test"}],
            name="test-dataset",
            dataset_id="ds-123",
        )
        config = {"key1": "value1", "key2": 42}

        context = optimizer._setup_optimization(
            prompt=simple_chat_prompt,
            dataset=mock_ds,
            metric=mock_metric,
            compute_baseline=False,
            experiment_config=config,
        )

        assert context.experiment_config == config

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
        mock_client.create_optimization.assert_not_called()

    def test_missing_optimization_id_creates_run_with_that_id(
        self,
        optimizer: ConcreteOptimizer,
        simple_chat_prompt,
        mock_opik_client,
        mock_metric: MetricFunction,
    ) -> None:
        """An optimization_id the backend doesn't know is created with that ID."""
        mock_client = mock_opik_client(optimization_id="opt-new")
        mock_client.get_optimization_by_id.side_effect = ApiError(
            status_code=404, body="not found"
        )

        context = optimizer._setup_optimization(
            prompt=simple_chat_prompt,
            dataset=make_mock_dataset([{"id": "1", "input": "test"}]),
            metric=mock_metric,
            optimization_id="opt-new",
        )

        create_kwargs = mock_client.create_optimization.call_args.kwargs
        assert create_kwargs["optimization_id"] == "opt-new"
        assert context.optimization_id == "opt-new"
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
                optimization_id="opt-x",
            )
        mock_client.create_optimization.assert_not_called()

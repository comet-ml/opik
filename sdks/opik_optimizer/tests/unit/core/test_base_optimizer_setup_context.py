"""Unit tests for BaseOptimizer._setup_optimization: prompt/context basics."""

# mypy: disable-error-code=no-untyped-def

from __future__ import annotations

from typing import Any

import pytest

from opik_optimizer.api_objects.types import MetricFunction
from opik_optimizer.core.state import OptimizationContext
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


class TestSetupOptimizationContext:
    def test_returns_optimization_context(
        self,
        optimizer: ConcreteOptimizer,
        simple_chat_prompt,
        mock_opik_client,
        mock_metric: MetricFunction,
    ) -> None:
        """_setup_optimization should return an OptimizationContext."""
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
        )

        assert isinstance(context, OptimizationContext)
        assert context.dataset is mock_ds
        assert context.metric is mock_metric
        assert context.is_single_prompt_optimization is True

    def test_normalizes_single_prompt_to_dict(
        self,
        optimizer: ConcreteOptimizer,
        simple_chat_prompt,
        mock_opik_client,
        mock_metric: MetricFunction,
    ) -> None:
        """Single ChatPrompt should be normalized to dict."""
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
        )

        assert isinstance(context.prompts, dict)
        assert simple_chat_prompt.name in context.prompts
        assert context.is_single_prompt_optimization is True

    def test_preserves_dict_prompt(
        self,
        optimizer: ConcreteOptimizer,
        simple_chat_prompt,
        mock_opik_client,
        mock_metric: MetricFunction,
    ) -> None:
        """Dict of prompts should be preserved."""
        mock_opik_client()
        mock_ds = make_mock_dataset(
            [{"id": "1", "input": "test"}],
            name="test-dataset",
            dataset_id="ds-123",
        )

        prompts = {"main": simple_chat_prompt}
        context = optimizer._setup_optimization(
            prompt=prompts,
            dataset=mock_ds,
            metric=mock_metric,
            compute_baseline=False,
        )

        assert context.prompts is prompts
        assert context.is_single_prompt_optimization is False

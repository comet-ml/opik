"""Unit tests for BaseOptimizer._setup_optimization."""

# mypy: disable-error-code=no-untyped-def

from __future__ import annotations

from unittest.mock import MagicMock

import pytest

from opik_optimizer.core.state import OptimizationContext
from tests.unit.fixtures.base_optimizer_test_helpers import ConcreteOptimizer
from tests.unit.test_helpers import make_mock_dataset


class TestSetupOptimization:
    """Tests for _setup_optimization method."""

    @pytest.fixture
    def optimizer(self) -> ConcreteOptimizer:
        return ConcreteOptimizer(model="gpt-4")

    @pytest.fixture
    def mock_metric(self):
        def metric(dataset_item, llm_output):
            _ = dataset_item, llm_output
            return 1.0

        metric.__name__ = "test_metric"
        return metric

    def test_returns_optimization_context(
        self,
        optimizer,
        simple_chat_prompt,
        mock_opik_client,
        mock_metric,
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
        optimizer,
        simple_chat_prompt,
        mock_opik_client,
        mock_metric,
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
        optimizer,
        simple_chat_prompt,
        mock_opik_client,
        mock_metric,
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

    def test_creates_agent_if_not_provided(
        self,
        optimizer,
        simple_chat_prompt,
        mock_opik_client,
        mock_metric,
    ) -> None:
        """Should create LiteLLMAgent if no agent provided."""
        mock_opik_client()
        from opik_optimizer.agents import LiteLLMAgent

        mock_ds = make_mock_dataset(
            [{"id": "1", "input": "test"}],
            name="test-dataset",
            dataset_id="ds-123",
        )

        context = optimizer._setup_optimization(
            prompt=simple_chat_prompt,
            dataset=mock_ds,
            metric=mock_metric,
            agent=None,
            compute_baseline=False,
        )

        assert isinstance(context.agent, LiteLLMAgent)

    def test_uses_provided_agent(
        self,
        optimizer,
        simple_chat_prompt,
        mock_opik_client,
        mock_metric,
    ) -> None:
        """Should use provided agent."""
        mock_opik_client()
        from opik_optimizer.agents import OptimizableAgent

        mock_ds = make_mock_dataset(
            [{"id": "1", "input": "test"}],
            name="test-dataset",
            dataset_id="ds-123",
        )
        mock_agent = MagicMock(spec=OptimizableAgent)

        context = optimizer._setup_optimization(
            prompt=simple_chat_prompt,
            dataset=mock_ds,
            metric=mock_metric,
            agent=mock_agent,
            compute_baseline=False,
        )

        assert context.agent is mock_agent

    def test_stores_extra_params(
        self,
        optimizer,
        simple_chat_prompt,
        mock_opik_client,
        mock_metric,
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

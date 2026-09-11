"""Unit tests for BaseOptimizer._setup_optimization: agent + project wiring."""

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


class TestSetupOptimizationAgent:
    def test_creates_agent_if_not_provided(
        self,
        optimizer: ConcreteOptimizer,
        simple_chat_prompt,
        mock_opik_client,
        mock_metric: MetricFunction,
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
        optimizer: ConcreteOptimizer,
        simple_chat_prompt,
        mock_opik_client,
        mock_metric: MetricFunction,
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

    def test_project_name_sets_optimizer_and_default_agent(
        self,
        optimizer: ConcreteOptimizer,
        simple_chat_prompt,
        mock_opik_client,
        mock_metric: MetricFunction,
    ) -> None:
        """project_name should update optimizer.project_name and be passed into default agent."""
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
            compute_baseline=False,
            project_name="TestProject",
            agent=None,  # ensure default agent is created
        )

        assert context.project_name == "TestProject"
        assert optimizer.project_name == "TestProject"
        assert isinstance(context.agent, LiteLLMAgent)
        assert context.agent.project_name == "TestProject"  # type: ignore[attr-defined]

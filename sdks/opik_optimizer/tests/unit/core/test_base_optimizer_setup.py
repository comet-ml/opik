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

    def test_stores_auto_continue_in_extra_params(
        self,
        optimizer,
        simple_chat_prompt,
        mock_opik_client,
        mock_metric,
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
        optimizer,
        simple_chat_prompt,
        mock_opik_client,
        mock_metric,
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

    def test_project_name_sets_optimizer_and_default_agent(
        self,
        optimizer,
        simple_chat_prompt,
        mock_opik_client,
        mock_metric,
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

    def test_optimization_id_fetches_existing_optimization(
        self,
        optimizer,
        simple_chat_prompt,
        mock_opik_client,
        mock_metric,
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

    def test_validation_dataset_selected_for_evaluation_and_split_tagged(
        self,
        optimizer,
        simple_chat_prompt,
        mock_opik_client,
        mock_metric,
    ) -> None:
        """validation_dataset should become evaluation_dataset and set dataset_split='validation'."""
        mock_opik_client()
        training_ds = make_mock_dataset(
            [{"id": "1", "question": "Q1", "answer": "A1"}],
            name="training",
            dataset_id="train-123",
        )
        validation_ds = make_mock_dataset(
            [{"id": "2", "question": "Q2", "answer": "A2"}],
            name="validation",
            dataset_id="val-123",
        )

        context = optimizer._setup_optimization(
            prompt=simple_chat_prompt,
            dataset=training_ds,
            metric=mock_metric,
            compute_baseline=False,
            validation_dataset=validation_ds,
        )

        assert context.validation_dataset is validation_ds
        assert context.evaluation_dataset is validation_ds
        assert context.dataset_split == "validation"

    def test_validation_dataset_enriches_experiment_config(
        self,
        optimizer,
        simple_chat_prompt,
        mock_opik_client,
        mock_metric,
    ) -> None:
        """When validation_dataset is provided, experiment_config should include its name/id."""
        mock_opik_client()
        training_ds = make_mock_dataset(
            [{"id": "1", "question": "Q1", "answer": "A1"}],
            name="training",
            dataset_id="train-123",
        )
        validation_ds = make_mock_dataset(
            [{"id": "2", "question": "Q2", "answer": "A2"}],
            name="validation",
            dataset_id="val-123",
        )

        context = optimizer._setup_optimization(
            prompt=simple_chat_prompt,
            dataset=training_ds,
            metric=mock_metric,
            compute_baseline=False,
            validation_dataset=validation_ds,
            experiment_config={},
        )

        assert context.experiment_config is not None
        assert context.experiment_config["validation_dataset"] == "validation"
        assert context.experiment_config["validation_dataset_id"] == "val-123"

    @pytest.mark.parametrize(
        "n_samples,total_items,expected",
        [
            (None, 3, None),
            (2, 3, 2),
            (5, 3, None),  # clamped to full dataset by setting to None
            (0, 3, 0),
        ],
    )
    def test_n_samples_normalization_against_evaluation_dataset_size(
        self,
        optimizer,
        simple_chat_prompt,
        mock_opik_client,
        mock_metric,
        n_samples: int | None,
        total_items: int,
        expected: int | None,
    ) -> None:
        """n_samples should be validated against evaluation dataset size (and clamped when too large)."""
        mock_opik_client()
        items = [
            {"id": str(i), "question": f"Q{i}", "answer": f"A{i}"}
            for i in range(total_items)
        ]
        training_ds = make_mock_dataset(items, name="training", dataset_id="train-123")

        context = optimizer._setup_optimization(
            prompt=simple_chat_prompt,
            dataset=training_ds,
            metric=mock_metric,
            compute_baseline=False,
            n_samples=n_samples,
        )

        assert context.n_samples == expected

    def test_n_samples_validated_against_validation_dataset_size(
        self,
        optimizer,
        simple_chat_prompt,
        mock_opik_client,
        mock_metric,
    ) -> None:
        """n_samples should be validated against validation split when validation_dataset is provided."""
        mock_opik_client()
        training_ds = make_mock_dataset(
            [{"id": "1", "question": "Q1", "answer": "A1"}],
            name="training",
            dataset_id="train-123",
        )
        validation_ds = make_mock_dataset(
            [{"id": "2", "question": "Q2", "answer": "A2"}],
            name="validation",
            dataset_id="val-123",
        )

        context = optimizer._setup_optimization(
            prompt=simple_chat_prompt,
            dataset=training_ds,
            metric=mock_metric,
            compute_baseline=False,
            validation_dataset=validation_ds,
            n_samples=2,  # larger than validation dataset size (1)
        )

        assert context.n_samples is None

    def test_empty_dataset_raises_error(
        self,
        optimizer,
        simple_chat_prompt,
        mock_opik_client,
        mock_metric,
    ) -> None:
        """Empty evaluation dataset should raise ValueError."""
        mock_opik_client()
        empty_ds = make_mock_dataset([], name="empty", dataset_id="ds-empty")

        with pytest.raises(ValueError, match="dataset is empty"):
            optimizer._setup_optimization(
                prompt=simple_chat_prompt,
                dataset=empty_ds,
                metric=mock_metric,
                compute_baseline=False,
            )

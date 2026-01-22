"""Unit tests for BaseOptimizer._setup_optimization: datasets + n_samples validation."""

# mypy: disable-error-code=no-untyped-def

from __future__ import annotations

from typing import Any
from unittest.mock import MagicMock

import pytest

from opik_optimizer.api_objects.types import MetricFunction
from tests.unit.fixtures.base_optimizer_test_helpers import ConcreteOptimizer
from tests.unit.fixtures.builders import make_mock_dataset


def _make_sequential_dataset(
    count: int, *, name: str = "sampling-dataset", dataset_id: str = "sampling-ds"
) -> MagicMock:
    items = [{"id": str(i), "value": i} for i in range(count)]
    return make_mock_dataset(items, name=name, dataset_id=dataset_id)


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


class TestSetupOptimizationDatasetsAndSamples:
    def test_validation_dataset_selected_for_evaluation_and_split_tagged(
        self,
        optimizer: ConcreteOptimizer,
        simple_chat_prompt,
        mock_opik_client,
        mock_metric: MetricFunction,
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
        optimizer: ConcreteOptimizer,
        simple_chat_prompt,
        mock_opik_client,
        mock_metric: MetricFunction,
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
        optimizer: ConcreteOptimizer,
        simple_chat_prompt,
        mock_opik_client,
        mock_metric: MetricFunction,
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
        optimizer: ConcreteOptimizer,
        simple_chat_prompt,
        mock_opik_client,
        mock_metric: MetricFunction,
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
        optimizer: ConcreteOptimizer,
        simple_chat_prompt,
        mock_opik_client,
        mock_metric: MetricFunction,
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

    def test_prepare_sampling_plan_respects_full_alias(
        self,
        optimizer: ConcreteOptimizer,
    ) -> None:
        dataset = _make_sequential_dataset(4)

        plan = optimizer._prepare_sampling_plan(
            dataset=dataset,
            n_samples="full",
            phase="eval",
        )

        assert plan.nb_samples is None
        assert plan.dataset_item_ids is None
        assert plan.mode.endswith(":full")

    def test_prepare_minibatch_plan_prefers_minibatch_count(
        self, optimizer: ConcreteOptimizer
    ) -> None:
        dataset = _make_sequential_dataset(10)

        plan = optimizer._prepare_minibatch_plan(
            dataset=dataset,
            n_samples=2,
            n_samples_minibatch=4,
            phase="minibatch",
        )

        assert plan.nb_samples == 4
        assert plan.mode.startswith("minibatch")
        assert plan.dataset_item_ids is not None
        assert len(plan.dataset_item_ids) == 4

    def test_prepare_minibatch_plan_clamps_to_dataset_size(
        self, optimizer: ConcreteOptimizer
    ) -> None:
        dataset = _make_sequential_dataset(3)

        plan = optimizer._prepare_minibatch_plan(
            dataset=dataset,
            n_samples=1,
            n_samples_minibatch=10,
            phase="minibatch",
        )

        assert plan.nb_samples == 3
        assert plan.dataset_item_ids is not None
        assert len(plan.dataset_item_ids) == 3

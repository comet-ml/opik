"""Unit tests for BaseOptimizer validation and small helper behaviors."""

# mypy: disable-error-code=no-untyped-def
from __future__ import annotations

from typing import Any

import pytest

from opik_optimizer.base_optimizer import BaseOptimizer
from opik_optimizer.core.state import AlgorithmResult
from tests.unit.fixtures.base_optimizer_test_helpers import ConcreteOptimizer
from tests.unit.test_helpers import make_mock_dataset


class TestValidateOptimizationInputs:
    """Tests for _validate_optimization_inputs method."""

    @pytest.fixture
    def optimizer(self) -> ConcreteOptimizer:
        return ConcreteOptimizer(model="gpt-4")

    @pytest.fixture
    def mock_dataset(self, mock_dataset):
        """Use the mock_dataset fixture from conftest."""
        return mock_dataset([{"id": "1", "question": "Q1", "answer": "A1"}])

    @pytest.fixture
    def mock_metric(self):
        def metric(dataset_item, llm_output):
            _ = dataset_item, llm_output
            return 1.0

        return metric

    def test_rejects_non_chatprompt(self, optimizer, mock_metric) -> None:
        """Should reject prompt that is not a ChatPrompt."""
        mock_ds = make_mock_dataset()

        with pytest.raises(ValueError, match="ChatPrompt"):
            optimizer._validate_optimization_inputs(
                "not a prompt",  # type: ignore[arg-type]
                mock_ds,
                mock_metric,
            )

    def test_rejects_dict_with_non_chatprompt_values(
        self, optimizer, mock_metric
    ) -> None:
        """Should reject dict containing non-ChatPrompt values."""
        mock_ds = make_mock_dataset()
        invalid_dict = {"main": "not a prompt"}

        with pytest.raises(ValueError, match="ChatPrompt"):
            optimizer._validate_optimization_inputs(
                invalid_dict,  # type: ignore[arg-type]
                mock_ds,
                mock_metric,
            )

    def test_rejects_non_dataset(
        self, optimizer, simple_chat_prompt, mock_metric
    ) -> None:
        """Should reject non-Dataset object."""
        with pytest.raises(ValueError, match="Dataset"):
            optimizer._validate_optimization_inputs(
                simple_chat_prompt,
                "not a dataset",  # type: ignore[arg-type]
                mock_metric,
            )

    def test_rejects_non_callable_metric(self, optimizer, simple_chat_prompt) -> None:
        """Should reject metric that is not callable."""
        mock_ds = make_mock_dataset()

        with pytest.raises(ValueError, match="function"):
            optimizer._validate_optimization_inputs(
                simple_chat_prompt,
                mock_ds,
                "not a function",  # type: ignore[arg-type]
            )

    def test_rejects_multimodal_when_not_supported(
        self, optimizer, multimodal_chat_prompt, mock_metric
    ) -> None:
        """Should reject multimodal prompts when support_content_parts=False."""
        mock_ds = make_mock_dataset()

        with pytest.raises(ValueError, match="content parts"):
            optimizer._validate_optimization_inputs(
                multimodal_chat_prompt,
                mock_ds,
                mock_metric,
                support_content_parts=False,
            )

    def test_accepts_multimodal_when_supported(
        self, optimizer, multimodal_chat_prompt, mock_metric
    ) -> None:
        """Should accept multimodal prompts when support_content_parts=True."""
        mock_ds = make_mock_dataset()

        optimizer._validate_optimization_inputs(
            multimodal_chat_prompt,
            mock_ds,
            mock_metric,
            support_content_parts=True,
        )


class TestMetricRequiredFields:
    """Tests for metric.required_fields validation on the evaluation dataset."""

    @pytest.fixture
    def optimizer(self) -> ConcreteOptimizer:
        return ConcreteOptimizer(model="gpt-4")

    def test_required_fields_enforced_on_validation_dataset_when_provided(
        self,
        optimizer: ConcreteOptimizer,
        simple_chat_prompt,
        mock_opik_client,
    ) -> None:
        """Metrics with required_fields should be validated against evaluation dataset (validation split)."""
        mock_opik_client()
        training_ds = make_mock_dataset(
            [{"id": "1", "question": "Q1", "answer": "A1"}],
            name="training",
            dataset_id="train-123",
        )
        # Missing the required "answer" field on the evaluation dataset
        validation_ds = make_mock_dataset(
            [{"id": "2", "question": "Q2"}],
            name="validation",
            dataset_id="val-123",
        )

        def metric_fn(dataset_item: dict[str, Any], llm_output: str) -> float:
            _ = dataset_item, llm_output
            return 0.0

        metric_fn.__name__ = "metric_with_required_fields"
        metric_fn.required_fields = ("answer",)  # type: ignore[attr-defined]

        with pytest.raises(ValueError, match="requires dataset fields"):
            optimizer._setup_optimization(
                prompt=simple_chat_prompt,
                dataset=training_ds,
                metric=metric_fn,  # type: ignore[arg-type]
                compute_baseline=False,
                validation_dataset=validation_ds,
            )


class TestOptimizationFlagsForUserSubclass:
    """A user subclass (capability flags left at their defaults) is still validated."""

    class _UserOptimizer(BaseOptimizer):
        def run_optimization(self, context):
            return AlgorithmResult(best_prompts=context.prompts, best_score=0.0)

    @staticmethod
    def _metric(dataset_item, llm_output):
        return 1.0

    def test_rejects_no_prompt_roles_and_no_tools(
        self, simple_chat_prompt, mock_opik_client
    ) -> None:
        mock_opik_client()
        with pytest.raises(ValueError, match="both disabled"):
            self._UserOptimizer(model="gpt-4")._setup_optimization(
                prompt=simple_chat_prompt,
                dataset=make_mock_dataset(),
                metric=self._metric,
                optimize_prompts=[],
            )

    def test_warns_and_ignores_unsupported_tool_optimization(
        self, chat_prompt_with_tools, mock_opik_client
    ) -> None:
        mock_opik_client()
        with pytest.warns(UserWarning, match="optimize_tools is not supported"):
            context = self._UserOptimizer(model="gpt-4")._setup_optimization(
                prompt=chat_prompt_with_tools,
                dataset=make_mock_dataset(),
                metric=self._metric,
                optimize_tools=True,
            )
        assert "optimize_tools" not in context.extra_params

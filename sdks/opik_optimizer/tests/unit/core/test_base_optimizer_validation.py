"""Unit tests for BaseOptimizer validation and small helper behaviors."""

# mypy: disable-error-code=no-untyped-def
from __future__ import annotations

import pytest

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

    def test_accepts_valid_single_prompt(
        self, optimizer, simple_chat_prompt, mock_dataset, mock_metric
    ) -> None:
        """Should accept a valid ChatPrompt, Dataset, and metric."""
        mock_ds = make_mock_dataset()

        optimizer._validate_optimization_inputs(
            simple_chat_prompt, mock_ds, mock_metric
        )

    def test_accepts_valid_prompt_dict(
        self, optimizer, simple_chat_prompt, mock_metric
    ) -> None:
        """Should accept a dict of ChatPrompt objects."""
        mock_ds = make_mock_dataset()
        prompt_dict = {"main": simple_chat_prompt}

        optimizer._validate_optimization_inputs(prompt_dict, mock_ds, mock_metric)

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


class TestSkipAndResultHelpers:
    """Tests for skip-threshold and result helper utilities."""

    @pytest.fixture
    def optimizer(self) -> ConcreteOptimizer:
        return ConcreteOptimizer(model="gpt-4")

    def test_should_skip_optimization_respects_defaults(self, optimizer) -> None:
        assert optimizer._should_skip_optimization(0.96) is True
        assert optimizer._should_skip_optimization(0.5) is False

    def test_should_skip_optimization_overrides(self, optimizer) -> None:
        assert optimizer._should_skip_optimization(0.5, perfect_score=0.5) is True
        assert (
            optimizer._should_skip_optimization(0.99, skip_perfect_score=False) is False
        )

    def test_select_result_prompts_single(self, optimizer, simple_chat_prompt) -> None:
        best_prompts = {"main": simple_chat_prompt}
        initial_prompts = {"main": simple_chat_prompt}
        result_prompt, result_initial = optimizer._select_result_prompts(
            best_prompts=best_prompts,
            initial_prompts=initial_prompts,
            is_single_prompt_optimization=True,
        )
        assert result_prompt is simple_chat_prompt
        assert result_initial is simple_chat_prompt

    def test_select_result_prompts_bundle(self, optimizer, simple_chat_prompt) -> None:
        best_prompts = {"main": simple_chat_prompt}
        initial_prompts = {"main": simple_chat_prompt}
        result_prompt, result_initial = optimizer._select_result_prompts(
            best_prompts=best_prompts,
            initial_prompts=initial_prompts,
            is_single_prompt_optimization=False,
        )
        assert result_prompt == best_prompts
        assert result_initial == initial_prompts

    def test_build_early_result_defaults(self, optimizer, simple_chat_prompt) -> None:
        result = optimizer._build_early_result(
            optimizer_name="ConcreteOptimizer",
            prompt=simple_chat_prompt,
            initial_prompt=simple_chat_prompt,
            score=0.75,
            metric_name="metric",
            details={"stopped_early": True},
            dataset_id="dataset-id",
            optimization_id="opt-id",
        )
        assert result.score == 0.75
        assert result.metric_name == "metric"
        assert result.initial_score == 0.75
        assert result.history == []
        assert result.details["stopped_early"] is True

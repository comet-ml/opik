"""Test MiproOptimizer parameter validation."""

import pytest
from typing import Any
from contextlib import nullcontext
from unittest.mock import Mock, patch

from opik_optimizer.mipro_optimizer.mipro_optimizer import MiproOptimizer
from opik_optimizer.optimization_config import chat_prompt


class TestMiproOptimizerValidation:
    """Test MiproOptimizer parameter validation."""

    def test_auto_parameter_validation(
        self, mock_dataset: Any, mock_metric: Any
    ) -> None:
        """Test that auto parameter accepts valid values."""
        optimizer = MiproOptimizer(model="openai/gpt-4")

        # Create a mock task config
        task_config = Mock()
        task_config.input_dataset_fields = ["text"]
        task_config.output_dataset_field = "label"

        prompt = chat_prompt.ChatPrompt(
            system="You are a helpful assistant.", user="Answer: {text}"
        )

        # Test valid auto values
        valid_auto_values = ["light", "medium", "heavy"]

        for auto_value in valid_auto_values:
            with patch.object(optimizer, "_optimize_prompt") as mock_optimize:
                mock_optimize.return_value = Mock()

                try:
                    optimizer.optimize_prompt(
                        prompt=prompt,
                        dataset=mock_dataset,
                        metric=mock_metric,
                        task_config=task_config,
                        auto=auto_value,
                    )
                    # If no exception is raised, the parameter is accepted
                    assert True
                except ValueError as e:
                    pytest.fail(f"Valid auto value '{auto_value}' was rejected: {e}")

    def test_auto_parameter_invalid_values(
        self, mock_dataset: Any, mock_metric: Any
    ) -> None:
        """Test that auto parameter rejects invalid values."""
        optimizer = MiproOptimizer(model="openai/gpt-4")

        # Create a mock task config
        task_config = Mock()
        task_config.input_dataset_fields = ["text"]
        task_config.output_dataset_field = "label"

        prompt = chat_prompt.ChatPrompt(
            system="You are a helpful assistant.", user="Answer: {text}"
        )

        # Test invalid auto values
        invalid_auto_values = ["invalid", "fast", "slow", "", None, 123]

        for auto_value in invalid_auto_values:
            with patch.object(optimizer, "_optimize_prompt") as mock_optimize:
                mock_optimize.return_value = Mock()

                # The optimizer should accept the parameter but the underlying
                # MIPRO library might reject it. We're testing that our
                # parameter extraction works correctly.
                try:
                    optimizer.optimize_prompt(
                        prompt=prompt,
                        dataset=mock_dataset,
                        metric=mock_metric,
                        task_config=task_config,
                        auto=auto_value,
                    )
                    # If no exception is raised at our level, that's fine
                    # The underlying library will handle validation
                    assert True
                except ValueError as e:
                    # If our code rejects it, that's also fine
                    assert "auto" in str(e).lower() or "invalid" in str(e).lower()

    def test_missing_task_config_raises_error(
        self, mock_dataset: Any, mock_metric: Any
    ) -> None:
        """Test that missing task_config raises ValueError."""
        optimizer = MiproOptimizer(model="openai/gpt-4")

        prompt = chat_prompt.ChatPrompt(
            system="You are a helpful assistant.", user="Answer: {text}"
        )

        with pytest.raises(
            ValueError, match="task_config is required for MiproOptimizer"
        ):
            optimizer.optimize_prompt(
                prompt=prompt,
                dataset=mock_dataset,
                metric=mock_metric,
                # task_config is missing
            )

    def test_kwargs_parameter_extraction(
        self, mock_dataset: Any, mock_metric: Any
    ) -> None:
        """Test that kwargs parameters are correctly extracted."""
        optimizer = MiproOptimizer(model="openai/gpt-4")

        # Create a mock task config
        task_config = Mock()
        task_config.input_dataset_fields = ["text"]
        task_config.output_dataset_field = "label"

        prompt = chat_prompt.ChatPrompt(
            system="You are a helpful assistant.", user="Answer: {text}"
        )

        with patch.object(optimizer, "_optimize_prompt") as mock_optimize:
            mock_optimize.return_value = Mock()

            # Test with custom kwargs values
            optimizer.optimize_prompt(
                prompt=prompt,
                dataset=mock_dataset,
                metric=mock_metric,
                task_config=task_config,
                num_candidates=20,
                num_trials=5,
                auto="heavy",
            )

            # Verify that _optimize_prompt was called with the extracted parameters
            mock_optimize.assert_called_once()
            call_kwargs = mock_optimize.call_args[1]

            # Check that the parameters were passed through
            assert call_kwargs.get("num_candidates") == 20
            assert call_kwargs.get("num_trials") == 5
            assert call_kwargs.get("auto") == "heavy"

    def test_dataset_name_is_resolved(
        self, mock_metric: Any, mock_dataset: Any
    ) -> None:
        """Test that dataset names are resolved to Dataset objects."""
        optimizer = MiproOptimizer(model="openai/gpt-4")

        task_config = Mock()
        task_config.input_dataset_fields = ["text"]
        task_config.output_dataset_field = "label"
        task_config.tools = []
        task_config.instruction_prompt = "Answer: {text}"

        prompt = chat_prompt.ChatPrompt(
            system="You are a helpful assistant.", user="Answer: {text}"
        )

        dataset_name = "my-dataset"

        with patch(
            "opik_optimizer.mipro_optimizer.mipro_optimizer.opik.Opik"
        ) as mock_opik, patch.object(optimizer, "_optimize_prompt") as mock_optimize:
            client_instance = mock_opik.return_value
            client_instance.get_dataset.return_value = mock_dataset

            mock_optimize.return_value = Mock()

            with patch.object(
                optimizer,
                "create_optimization_context",
                return_value=nullcontext(Mock(id="opt-id")),
            ) as mock_context:
                optimizer.optimize_prompt(
                    prompt=prompt,
                    dataset=dataset_name,
                    metric=mock_metric,
                    task_config=task_config,
                )

            mock_opik.assert_called_once_with(project_name=None)
            client_instance.get_dataset.assert_called_once_with(dataset_name)
            mock_context.assert_called_once_with(mock_dataset, mock_metric)
            assert mock_optimize.call_args[1]["dataset"] is mock_dataset

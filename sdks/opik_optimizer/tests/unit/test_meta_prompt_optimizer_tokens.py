"""
Unit tests for MetaPromptOptimizer token calculation and context fitting logic.
"""

import pytest
from unittest.mock import Mock, patch
from opik_optimizer.algorithms.meta_prompt_optimizer.meta_prompt_optimizer import (
    MetaPromptOptimizer,
)
from opik_optimizer.algorithms.meta_prompt_optimizer.ops.context_ops import (
    get_task_context,
    count_tokens,
)


class TestTokenCalculation:
    """Test token calculation logic using litellm."""

    def test_calculate_max_context_tokens_with_known_model(self) -> None:
        """Test token calculation with a known model (gpt-4)."""
        with patch("litellm.get_max_tokens") as mock_get_max_tokens:
            mock_get_max_tokens.return_value = 128000  # gpt-4 context window

            optimizer = MetaPromptOptimizer(model="gpt-4")

            # Should be 25% of 128000 = 32000, but capped at 10000
            assert optimizer.max_context_tokens == 10000
            mock_get_max_tokens.assert_called_once_with("gpt-4")

    def test_calculate_max_context_tokens_with_smaller_model(self) -> None:
        """Test token calculation with a smaller model."""
        with patch("litellm.get_max_tokens") as mock_get_max_tokens:
            mock_get_max_tokens.return_value = 16000  # gpt-3.5-turbo context

            optimizer = MetaPromptOptimizer(model="gpt-3.5-turbo")

            # Should be 25% of 16000 = 4000 (under the cap)
            assert optimizer.max_context_tokens == 4000
            mock_get_max_tokens.assert_called_once_with("gpt-3.5-turbo")

    def test_calculate_max_context_tokens_custom_model_fallback(self) -> None:
        """Test fallback to absolute max for custom models."""
        with patch("litellm.get_max_tokens") as mock_get_max_tokens:
            mock_get_max_tokens.side_effect = Exception("Model not found")

            optimizer = MetaPromptOptimizer(model="custom-model-xyz")

            # Should fall back to DEFAULT_DATASET_CONTEXT_MAX_TOKENS
            assert optimizer.max_context_tokens == 10000

    def test_calculate_max_context_tokens_applies_absolute_max(self) -> None:
        """Test that absolute max is always applied as safety cap."""
        with patch("litellm.get_max_tokens") as mock_get_max_tokens:
            # Simulate a model with huge context (e.g., Claude 3.5 with 200k)
            mock_get_max_tokens.return_value = 200000

            optimizer = MetaPromptOptimizer(model="claude-3-5-sonnet-20241022")

            # Should be capped at 10000 even though 25% of 200k = 50k
            assert optimizer.max_context_tokens == 10000

    def test_constants_are_correctly_defined(self) -> None:
        """Test that all token-related constants are properly defined."""
        assert MetaPromptOptimizer.DEFAULT_DATASET_CONTEXT_MAX_TOKENS == 10000
        assert MetaPromptOptimizer.DEFAULT_DATASET_CONTEXT_RATIO == 0.25


class TestContextOpsTokenCounting:
    """Test token counting functionality in context_ops."""

    def test_count_tokens_with_litellm(self) -> None:
        """Test token counting using litellm."""
        with patch(
            "opik_optimizer.algorithms.meta_prompt_optimizer.ops.context_ops.token_counter"
        ) as mock_counter:
            mock_counter.return_value = 100

            result = count_tokens("This is a test string", model="gpt-4")

            assert result == 100
            mock_counter.assert_called_once()
            # Verify it was called with messages format
            call_args = mock_counter.call_args
            assert call_args[1]["model"] == "gpt-4"
            assert call_args[1]["messages"][0]["role"] == "user"
            assert call_args[1]["messages"][0]["content"] == "This is a test string"

    def test_count_tokens_fallback_when_litellm_unavailable(self) -> None:
        """Test fallback token counting when litellm fails."""
        with patch(
            "opik_optimizer.algorithms.meta_prompt_optimizer.ops.context_ops.LITELLM_TOKEN_COUNTER_AVAILABLE",
            False,
        ):
            test_string = "x" * 40  # Exactly 40 characters
            result = count_tokens(test_string, model="gpt-4")

            # Fallback: ~1 token per 4 chars = 40/4 = 10 tokens
            assert result == 10


class TestAdaptiveContextFitting:
    """Test adaptive fitting logic for dataset context."""

    def create_mock_dataset(
        self, num_items: int = 5, long_values: bool = False
    ) -> Mock:
        """Helper to create a mock dataset."""
        mock_dataset = Mock()
        items = []
        for i in range(num_items):
            value_text = "x" * 500 if long_values else f"Sample text {i}"
            items.append(
                {
                    "id": f"item_{i}",
                    "question": f"Question {i}?",
                    "context": value_text,
                    "answer": f"Answer {i}",  # Should be excluded
                }
            )
        mock_dataset.get_items.return_value = items
        return mock_dataset

    def test_adaptive_fitting_reduces_examples(self) -> None:
        """Test that adaptive fitting reduces number of examples when over budget."""
        mock_dataset = self.create_mock_dataset(num_items=5, long_values=True)
        mock_metric = Mock()

        with patch(
            "opik_optimizer.algorithms.meta_prompt_optimizer.ops.context_ops.count_tokens"
        ) as mock_count:
            # First call: too many tokens, second call: fits
            mock_count.side_effect = [3000, 1500]

            context = get_task_context(
                dataset=mock_dataset,
                metric=mock_metric,
                num_examples=5,
                max_tokens=2000,
                model="gpt-4",
                verbose=0,
            )

            # Should have called count_tokens twice (reduced from 5 to 4 examples)
            assert mock_count.call_count == 2
            assert "Example" in context

    def test_adaptive_fitting_reduces_truncation(self) -> None:
        """Test that adaptive fitting reduces truncation length when needed."""
        mock_dataset = self.create_mock_dataset(num_items=1, long_values=True)
        mock_metric = Mock()

        with patch(
            "opik_optimizer.algorithms.meta_prompt_optimizer.ops.context_ops.count_tokens"
        ) as mock_count:
            # First call: still too big, second call: fits with reduced truncation
            mock_count.side_effect = [2500, 1800]

            context = get_task_context(
                dataset=mock_dataset,
                metric=mock_metric,
                num_examples=1,
                max_tokens=2000,
                model="gpt-4",
                verbose=0,
            )

            # Should have reduced truncation limit
            assert mock_count.call_count == 2
            assert "..." in context  # Indicates truncation happened

    def test_adaptive_fitting_returns_minimal_when_cannot_fit(self) -> None:
        """Test that minimal context is returned when cannot fit within budget."""
        mock_dataset = self.create_mock_dataset(num_items=1, long_values=True)
        mock_metric = Mock()

        with patch(
            "opik_optimizer.algorithms.meta_prompt_optimizer.ops.context_ops.count_tokens"
        ) as mock_count:
            # Always return too many tokens
            mock_count.return_value = 5000

            with patch(
                "opik_optimizer.algorithms.meta_prompt_optimizer.ops.context_ops.logger"
            ) as mock_logger:
                context = get_task_context(
                    dataset=mock_dataset,
                    metric=mock_metric,
                    num_examples=1,
                    max_tokens=2000,
                    model="gpt-4",
                    verbose=0,
                )

                # Should log warning about not fitting
                mock_logger.warning.assert_called()
                assert "Cannot fit" in mock_logger.warning.call_args[0][0]
                # Should still return context (minimal)
                assert len(context) > 0


class TestColumnSelection:
    """Test column selection functionality."""

    def test_column_selection_filters_correctly(self) -> None:
        """Test that column selection filters dataset fields."""
        mock_dataset = Mock()
        mock_dataset.get_items.return_value = [
            {
                "id": "1",
                "question": "What is 2+2?",
                "context": "Math problem",
                "hint": "Think about addition",
                "answer": "4",  # Should be excluded (output field)
            }
        ]
        mock_metric = Mock()

        with patch(
            "opik_optimizer.algorithms.meta_prompt_optimizer.ops.context_ops.count_tokens"
        ) as mock_count:
            mock_count.return_value = 100  # Fits within budget

            context = get_task_context(
                dataset=mock_dataset,
                metric=mock_metric,
                num_examples=1,
                columns=["question", "hint"],  # Only these columns
                max_tokens=2000,
                model="gpt-4",
                verbose=0,
            )

            # Should include selected columns
            assert "{question}" in context
            assert "{hint}" in context
            # Should NOT include unselected input column
            assert "{context}" not in context
            # Should NOT include output column
            assert "{answer}" not in context

    def test_column_selection_with_invalid_columns(self) -> None:
        """Test that invalid column names fall back to all input fields."""
        mock_dataset = Mock()
        mock_dataset.get_items.return_value = [
            {
                "id": "1",
                "question": "What is 2+2?",
                "context": "Math problem",
                "answer": "4",
            }
        ]
        mock_metric = Mock()

        with patch(
            "opik_optimizer.algorithms.meta_prompt_optimizer.ops.context_ops.count_tokens"
        ) as mock_count:
            mock_count.return_value = 100

            with patch(
                "opik_optimizer.algorithms.meta_prompt_optimizer.ops.context_ops.logger"
            ) as mock_logger:
                context = get_task_context(
                    dataset=mock_dataset,
                    metric=mock_metric,
                    num_examples=1,
                    columns=["nonexistent_col"],  # Invalid column
                    max_tokens=2000,
                    model="gpt-4",
                    verbose=0,
                )

                # Should log warning
                mock_logger.warning.assert_called()
                # Should fall back to all input columns
                assert "{question}" in context
                assert "{context}" in context


class TestMetadataMapping:
    """Test that metadata is properly mapped."""

    def test_optimizer_metadata_includes_token_budget(self) -> None:
        """Test that optimizer metadata includes max_context_tokens."""
        with patch("litellm.get_max_tokens") as mock_get_max_tokens:
            mock_get_max_tokens.return_value = 16000

            optimizer = MetaPromptOptimizer(model="gpt-3.5-turbo")
            metadata = optimizer.get_optimizer_metadata()

            assert "max_context_tokens" in metadata
            assert metadata["max_context_tokens"] == 4000  # 25% of 16000

    def test_optimizer_metadata_includes_all_parameters(self) -> None:
        """Test that all optimizer parameters are in metadata."""
        optimizer = MetaPromptOptimizer(
            model="gpt-4",
            prompts_per_round=5,
            num_task_examples=4,
            task_context_columns=["question", "context"],
        )
        metadata = optimizer.get_optimizer_metadata()

        assert metadata["prompts_per_round"] == 5
        assert metadata["num_task_examples"] == 4
        assert metadata["task_context_columns"] == ["question", "context"]
        assert "max_context_tokens" in metadata
        assert "hall_of_fame_size" in metadata
        assert "pattern_extraction_interval" in metadata
        assert "pattern_injection_rate" in metadata


if __name__ == "__main__":
    pytest.main([__file__, "-v"])

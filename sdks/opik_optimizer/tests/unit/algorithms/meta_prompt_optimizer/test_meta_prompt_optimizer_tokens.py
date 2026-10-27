"""
Unit tests for MetaPromptOptimizer token calculation and context fitting logic.
"""

from unittest.mock import Mock, patch
from opik_optimizer.algorithms.meta_prompt_optimizer.ops.context_learning_ops import (
    get_task_context,
)


def _metric_with_name(name: str = "mock_metric") -> Mock:
    metric = Mock()
    metric.__name__ = name
    return metric


class TestAdaptiveContextFitting:
    """Test adaptive fitting logic for dataset context."""

    def create_mock_dataset(
        self, num_items: int = 5, long_values: bool = False
    ) -> Mock:
        """Helper to create a mock dataset."""
        mock_dataset = Mock()
        items = []
        for i in range(num_items):
            # Use longer values (2500 chars) to ensure truncation actually happens
            # when max_value_length is reduced during adaptive fitting
            value_text = "x" * 2500 if long_values else f"Sample text {i}"
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
        mock_metric = _metric_with_name()

        with patch("opik_optimizer.utils.token.count_tokens") as mock_count:
            # First call: too many tokens, second call: fits
            mock_count.side_effect = [3000, 1500]

            context, token_count = get_task_context(
                dataset=mock_dataset,
                metric=mock_metric,
                num_examples=5,
                max_tokens=2000,
                model="gpt-4",
            )

            # Should have called count_tokens twice (reduced from 5 to 4 examples)
            assert mock_count.call_count == 2
            assert "Example" in context

    def test_adaptive_fitting_reduces_truncation(self) -> None:
        """Test that adaptive fitting reduces truncation length when needed."""
        mock_dataset = self.create_mock_dataset(num_items=1, long_values=True)
        mock_metric = _metric_with_name()

        with patch("opik_optimizer.utils.token.count_tokens") as mock_count:
            # First call: still too big, second call: fits with reduced truncation
            mock_count.side_effect = [2500, 1800]

            context, token_count = get_task_context(
                dataset=mock_dataset,
                metric=mock_metric,
                num_examples=1,
                max_tokens=2000,
                model="gpt-4",
            )

            # Should have reduced truncation limit
            assert mock_count.call_count == 2
            assert "..." in context  # Indicates truncation happened

    def test_adaptive_fitting_returns_minimal_when_cannot_fit(self) -> None:
        """Test that minimal context is returned when cannot fit within budget."""
        mock_dataset = self.create_mock_dataset(num_items=1, long_values=True)
        mock_metric = _metric_with_name()

        with patch("opik_optimizer.utils.token.count_tokens") as mock_count:
            # Always return too many tokens
            mock_count.return_value = 5000

            with patch(
                "opik_optimizer.algorithms.meta_prompt_optimizer.ops.context_learning_ops.logger"
            ) as mock_logger:
                context, token_count = get_task_context(
                    dataset=mock_dataset,
                    metric=mock_metric,
                    num_examples=1,
                    max_tokens=2000,
                    model="gpt-4",
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
        mock_metric = _metric_with_name()

        with patch("opik_optimizer.utils.token.count_tokens") as mock_count:
            mock_count.return_value = 100  # Fits within budget

            context, token_count = get_task_context(
                dataset=mock_dataset,
                metric=mock_metric,
                num_examples=1,
                columns=["question", "hint"],  # Only these columns
                max_tokens=2000,
                model="gpt-4",
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
        mock_metric = _metric_with_name()

        with patch("opik_optimizer.utils.token.count_tokens") as mock_count:
            mock_count.return_value = 100

            with patch(
                "opik_optimizer.algorithms.meta_prompt_optimizer.ops.context_learning_ops.logger"
            ) as mock_logger:
                context, token_count = get_task_context(
                    dataset=mock_dataset,
                    metric=mock_metric,
                    num_examples=1,
                    columns=["nonexistent_col"],  # Invalid column
                    max_tokens=2000,
                )

                # Should log warning
                mock_logger.warning.assert_called()
                # Should fall back to all input columns
                assert "{question}" in context
                assert "{context}" in context

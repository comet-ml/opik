"""
Tests for OptimizationResult model.

This module tests the OptimizationResult Pydantic model,
including validation, serialization, and field requirements.
"""

import pytest
from pydantic import ValidationError

from opik_optimizer.optimization_result import OptimizationResult


class TestOptimizationResult:
    """Test OptimizationResult model functionality."""

    def test_optimization_result_creation(self) -> None:
        """Test creating a valid OptimizationResult."""
        result = OptimizationResult(
            optimizer="TestOptimizer",
            prompt=[{"role": "user", "content": "test prompt"}],
            score=0.85,
            metric_name="accuracy",
            history=[{"iteration": 1, "score": 0.8}],
        )

        assert result.optimizer == "TestOptimizer"
        assert result.score == 0.85
        assert result.metric_name == "accuracy"
        assert result.history == [{"iteration": 1, "score": 0.8}]

    def test_optimization_result_missing_required_field(self) -> None:
        """Test that missing required fields raise ValidationError."""
        # Missing metric_name field
        with pytest.raises(ValidationError) as exc_info:
            OptimizationResult(
                optimizer="TestOptimizer",
                prompt=[{"role": "user", "content": "test prompt"}],
                score=0.85,
            )

        assert "metric_name" in str(exc_info.value)

    def test_optimization_result_serialization(self) -> None:
        """Test that OptimizationResult can be serialized."""
        result = OptimizationResult(
            optimizer="TestOptimizer",
            prompt=[{"role": "user", "content": "test prompt"}],
            score=0.85,
            metric_name="accuracy",
            history=[{"iteration": 1}],
        )

        # Should be able to convert to dict
        result_dict = result.model_dump()
        assert isinstance(result_dict, dict)
        assert result_dict["optimizer"] == "TestOptimizer"
        assert result_dict["score"] == 0.85
        assert result_dict["metric_name"] == "accuracy"

    def test_optimization_result_prompt_format(self) -> None:
        """Test that prompt field accepts correct format."""
        # Valid prompt format
        result = OptimizationResult(
            optimizer="TestOptimizer",
            prompt=[
                {"role": "system", "content": "You are a helpful assistant."},
                {"role": "user", "content": "What is AI?"},
            ],
            score=0.85,
            metric_name="accuracy",
        )

        assert len(result.prompt) == 2
        assert result.prompt[0]["role"] == "system"
        assert result.prompt[1]["role"] == "user"

    def test_optimization_result_invalid_prompt_format(self) -> None:
        """Test that invalid prompt format raises ValidationError."""
        # Invalid prompt format - should be list[dict[str, str]]
        with pytest.raises(ValidationError):
            OptimizationResult(
                optimizer="TestOptimizer",
                prompt="invalid string prompt",  # Should be list[dict[str, str]]
                score=0.85,
                metric_name="accuracy",
            )

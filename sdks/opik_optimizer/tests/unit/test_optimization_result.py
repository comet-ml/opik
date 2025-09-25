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

    def test_optimization_result_creation(self):
        """Test creating a valid OptimizationResult."""
        result = OptimizationResult(
            optimizer="TestOptimizer",
            prompt=[{"role": "user", "content": "test prompt"}],
            score=0.85,
            metric_name="accuracy",
            history=[{"iteration": 1, "score": 0.8}]
        )
        
        assert result.optimizer == "TestOptimizer"
        assert result.score == 0.85
        assert result.metric_name == "accuracy"
        assert result.history == [{"iteration": 1, "score": 0.8}]

    def test_optimization_result_required_fields(self):
        """Test that all required fields are present."""
        result = OptimizationResult(
            optimizer="TestOptimizer",
            prompt=[{"role": "user", "content": "test prompt"}],
            score=0.85,
            metric_name="accuracy"
        )
        
        # Check that all required fields are present
        assert hasattr(result, 'optimizer')
        assert hasattr(result, 'prompt')
        assert hasattr(result, 'score')
        assert hasattr(result, 'metric_name')

    def test_optimization_result_missing_required_field(self):
        """Test that missing required fields raise ValidationError."""
        # Missing metric_name field
        with pytest.raises(ValidationError) as exc_info:
            OptimizationResult(
                optimizer="TestOptimizer",
                prompt=[{"role": "user", "content": "test prompt"}],
                score=0.85
            )
        
        assert "metric_name" in str(exc_info.value)

    def test_optimization_result_field_types(self):
        """Test that field types are validated correctly."""
        # Valid types
        result = OptimizationResult(
            optimizer="TestOptimizer",  # str
            prompt=[{"role": "user", "content": "test"}],  # list[dict[str, str]]
            score=0.85,  # float
            metric_name="accuracy",  # str
            history=[{"key": "value"}],  # list
            optimization_id="opt-123",  # Optional str
            dataset_id="ds-456",  # Optional str
            initial_prompt=[{"role": "user", "content": "initial"}],  # Optional
            initial_score=0.7,  # Optional float
            details={"key": "value"},  # dict
            llm_calls=10,  # Optional int
            demonstrations=[{"example": "data"}],  # Optional list
            mipro_prompt="test prompt",  # Optional str
            tool_prompts={"tool1": "prompt1"}  # Optional dict
        )
        
        assert isinstance(result.optimizer, str)
        assert isinstance(result.prompt, list)
        assert isinstance(result.score, float)
        assert isinstance(result.metric_name, str)
        assert isinstance(result.history, list)

    def test_optimization_result_serialization(self):
        """Test that OptimizationResult can be serialized."""
        result = OptimizationResult(
            optimizer="TestOptimizer",
            prompt=[{"role": "user", "content": "test prompt"}],
            score=0.85,
            metric_name="accuracy",
            history=[{"iteration": 1}]
        )
        
        # Should be able to convert to dict
        result_dict = result.model_dump()
        assert isinstance(result_dict, dict)
        assert result_dict["optimizer"] == "TestOptimizer"
        assert result_dict["score"] == 0.85
        assert result_dict["metric_name"] == "accuracy"

    def test_optimization_result_default_values(self):
        """Test OptimizationResult with default values."""
        result = OptimizationResult(
            optimizer="TestOptimizer",
            prompt=[{"role": "user", "content": "test prompt"}],
            score=0.0,
            metric_name="accuracy"
        )
        
        assert result.score == 0.0
        assert result.history == []
        assert result.details == {}
        assert result.optimization_id is None
        assert result.dataset_id is None
        assert result.initial_prompt is None
        assert result.initial_score is None
        assert result.llm_calls is None
        assert result.demonstrations is None
        assert result.mipro_prompt is None
        assert result.tool_prompts is None

    def test_optimization_result_with_optional_fields(self):
        """Test OptimizationResult with optional fields populated."""
        result = OptimizationResult(
            optimizer="TestOptimizer",
            prompt=[{"role": "user", "content": "optimized prompt"}],
            score=0.95,
            metric_name="accuracy",
            optimization_id="opt-123",
            dataset_id="ds-456",
            initial_prompt=[{"role": "user", "content": "initial prompt"}],
            initial_score=0.7,
            details={"iterations": 5, "converged": True},
            history=[{"iteration": 1, "score": 0.7}, {"iteration": 2, "score": 0.8}],
            llm_calls=15,
            demonstrations=[{"input": "test", "output": "result"}],
            mipro_prompt="MIPRO optimized prompt",
            tool_prompts={"tool1": "prompt1", "tool2": "prompt2"}
        )
        
        assert result.optimization_id == "opt-123"
        assert result.dataset_id == "ds-456"
        assert result.initial_prompt == [{"role": "user", "content": "initial prompt"}]
        assert result.initial_score == 0.7
        assert result.details == {"iterations": 5, "converged": True}
        assert len(result.history) == 2
        assert result.llm_calls == 15
        assert len(result.demonstrations) == 1
        assert result.mipro_prompt == "MIPRO optimized prompt"
        assert result.tool_prompts == {"tool1": "prompt1", "tool2": "prompt2"}

    def test_optimization_result_prompt_format(self):
        """Test that prompt field accepts correct format."""
        # Valid prompt format
        result = OptimizationResult(
            optimizer="TestOptimizer",
            prompt=[
                {"role": "system", "content": "You are a helpful assistant."},
                {"role": "user", "content": "What is AI?"}
            ],
            score=0.85,
            metric_name="accuracy"
        )
        
        assert len(result.prompt) == 2
        assert result.prompt[0]["role"] == "system"
        assert result.prompt[1]["role"] == "user"

    def test_optimization_result_invalid_prompt_format(self):
        """Test that invalid prompt format raises ValidationError."""
        # Invalid prompt format - should be list[dict[str, str]]
        with pytest.raises(ValidationError):
            OptimizationResult(
                optimizer="TestOptimizer",
                prompt="invalid string prompt",  # Should be list[dict[str, str]]
                score=0.85,
                metric_name="accuracy"
            )
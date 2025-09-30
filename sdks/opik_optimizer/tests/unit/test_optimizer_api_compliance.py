"""
Production API Compliance Tests for Opik Optimizers.

This file contains comprehensive tests to ensure all optimizers comply with
the production API specification for chaining and UI integration.

Tests cover:
1. Method signature consistency
2. Return type standardization
3. Input validation consistency
4. Output signature compliance
5. Serialization compatibility
6. Error handling standardization
"""

import pytest
from unittest.mock import Mock
import inspect
from typing import Any

from opik import Dataset
from opik_optimizer import (
    FewShotBayesianOptimizer,
    GepaOptimizer,
    MetaPromptOptimizer,
    EvolutionaryOptimizer,
)
from opik_optimizer.optimization_result import OptimizationResult
from opik_optimizer.optimization_config import chat_prompt
from collections.abc import Callable


class TestOptimizerAPICompliance:
    """Test that all optimizers comply with the production API specification."""

    @pytest.fixture
    def mock_prompt(self) -> Mock:
        """Create a mock ChatPrompt for testing."""
        prompt = Mock(spec=chat_prompt.ChatPrompt)
        prompt.get_messages.return_value = [{"role": "user", "content": "test"}]
        return prompt

    @pytest.fixture
    def mock_dataset(self) -> Mock:
        """Create a mock Dataset for testing."""
        dataset = Mock(spec=Dataset)
        dataset.__len__ = Mock(return_value=2)
        dataset.__iter__ = Mock(
            return_value=iter(
                [
                    Mock(
                        input={"query": "test1"}, expected_output={"answer": "answer1"}
                    ),
                    Mock(
                        input={"query": "test2"}, expected_output={"answer": "answer2"}
                    ),
                ]
            )
        )
        return dataset

    @pytest.fixture
    def mock_metric(self) -> Callable[[Any, Any], float]:
        """Create a mock metric function for testing."""

        def metric(dataset_item: Any, llm_output: Any) -> float:
            return 0.8

        return metric

    def test_optimize_prompt_signature_consistency(self) -> None:
        """Test that all optimizers have identical optimize_prompt signatures."""
        optimizers: list[type[Any]] = [
            FewShotBayesianOptimizer,
            GepaOptimizer,
            MetaPromptOptimizer,
            EvolutionaryOptimizer,
        ]

        # Get signatures from all optimizers
        signatures = []
        for optimizer_class in optimizers:
            sig = inspect.signature(optimizer_class.optimize_prompt)
            signatures.append((optimizer_class.__name__, sig))

        # Check that all signatures are identical
        first_sig = signatures[0][1]
        for name, sig in signatures[1:]:
            assert sig == first_sig, (
                f"{name} has different signature than {signatures[0][0]}\n"
                f"Expected: {first_sig}\n"
                f"Got: {sig}"
            )

    def test_optimize_prompt_parameter_order(self) -> None:
        """Test that all optimizers have consistent parameter order."""
        optimizers: list[type[Any]] = [
            FewShotBayesianOptimizer,
            GepaOptimizer,
            MetaPromptOptimizer,
            EvolutionaryOptimizer,
        ]

        expected_params = [
            "self",
            "prompt",
            "dataset",
            "metric",
            "experiment_config",
            "n_samples",
            "auto_continue",
            "agent_class",
        ]

        for optimizer_class in optimizers:
            sig = inspect.signature(optimizer_class.optimize_prompt)
            param_names = list(sig.parameters.keys())

            # Check that required parameters are in the correct order
            for i, expected_param in enumerate(expected_params):
                assert param_names[i] == expected_param, (
                    f"{optimizer_class.__name__} parameter {i} should be '{expected_param}', "
                    f"got '{param_names[i]}'"
                )

    def test_optimize_prompt_return_type_consistency(self) -> None:
        """Test that all optimizers return OptimizationResult."""
        optimizers: list[type[Any]] = [
            FewShotBayesianOptimizer,
            GepaOptimizer,
            MetaPromptOptimizer,
            EvolutionaryOptimizer,
        ]

        for optimizer_class in optimizers:
            sig = inspect.signature(optimizer_class.optimize_prompt)
            return_annotation = sig.return_annotation

            # Check that return annotation is OptimizationResult
            assert return_annotation == OptimizationResult, (
                f"{optimizer_class.__name__} should return OptimizationResult, "
                f"got {return_annotation}"
            )

            # Check that it's not a string annotation
            assert not isinstance(return_annotation, str), (
                f"{optimizer_class.__name__} has string return annotation: {return_annotation}"
            )

    def test_input_validation_consistency(self) -> None:
        """Test that all optimizers have consistent input validation."""
        # Create optimizer instances with required parameters
        optimizers = [
            FewShotBayesianOptimizer(model="gpt-4o-mini"),
            GepaOptimizer(model="gpt-4o-mini"),
            MetaPromptOptimizer(model="gpt-4o-mini"),
            EvolutionaryOptimizer(model="gpt-4o-mini"),
        ]

        for optimizer in optimizers:
            # Test invalid prompt type
            with pytest.raises(ValueError, match="Prompt must be a ChatPrompt object"):
                optimizer.optimize_prompt(
                    prompt="invalid_prompt",  # type: ignore[arg-type]
                    dataset=Mock(spec=Dataset),
                    metric=lambda x, y: 0.8,
                )

            # Test invalid dataset type
            with pytest.raises(ValueError, match="Dataset must be a Dataset object"):
                optimizer.optimize_prompt(
                    prompt=Mock(spec=chat_prompt.ChatPrompt),
                    dataset="invalid_dataset",  # type: ignore[arg-type]
                    metric=lambda x, y: 0.8,
                )

            # Test invalid metric type
            with pytest.raises(ValueError, match="Metric must be.*function"):
                optimizer.optimize_prompt(
                    prompt=Mock(spec=chat_prompt.ChatPrompt),
                    dataset=Mock(spec=Dataset),
                    metric="invalid_metric",  # type: ignore[arg-type]
                )

    def test_optimization_result_structure(self) -> None:
        """Test that OptimizationResult has the expected structure."""
        # Test creating an OptimizationResult with all fields
        result = OptimizationResult(
            optimizer="TestOptimizer",
            prompt=[{"role": "user", "content": "test"}],
            score=0.8,
            metric_name="test_metric",
            optimization_id="test_opt_id",
            dataset_id="test_dataset_id",
            initial_prompt=[{"role": "user", "content": "initial"}],
            initial_score=0.6,
            details={"test": "value"},
            history=[{"step": 1, "score": 0.7}],
            llm_calls=10,
            demonstrations=[{"input": "test", "output": "result"}],
            mipro_prompt="test prompt",
            tool_prompts={"tool1": "description"},
        )

        # Verify all expected fields are present and accessible
        assert result.optimizer == "TestOptimizer"
        assert result.prompt == [{"role": "user", "content": "test"}]
        assert result.score == 0.8
        assert result.metric_name == "test_metric"
        assert result.optimization_id == "test_opt_id"
        assert result.dataset_id == "test_dataset_id"
        assert result.initial_prompt == [{"role": "user", "content": "initial"}]
        assert result.initial_score == 0.6
        assert result.details == {"test": "value"}
        assert result.history == [{"step": 1, "score": 0.7}]
        assert result.llm_calls == 10
        assert result.demonstrations == [{"input": "test", "output": "result"}]
        assert result.mipro_prompt == "test prompt"
        assert result.tool_prompts == {"tool1": "description"}

    def test_optimization_result_serialization(self) -> None:
        """Test that OptimizationResult can be serialized properly."""
        result = OptimizationResult(
            optimizer="TestOptimizer",
            prompt=[{"role": "user", "content": "test"}],
            score=0.8,
            metric_name="test_metric",
            details={"test": "value"},
            history=[{"step": 1, "score": 0.7}],
        )

        # Test model_dump
        dumped = result.model_dump()
        assert isinstance(dumped, dict)
        assert dumped["optimizer"] == "TestOptimizer"
        assert dumped["score"] == 0.8
        assert dumped["metric_name"] == "test_metric"

        # Test that it can be reconstructed
        reconstructed = OptimizationResult(**dumped)
        assert reconstructed.optimizer == result.optimizer
        assert reconstructed.score == result.score
        assert reconstructed.metric_name == result.metric_name

    def test_optimization_result_string_representation(self) -> None:
        """Test that OptimizationResult has proper string representation."""
        result = OptimizationResult(
            optimizer="TestOptimizer",
            prompt=[{"role": "user", "content": "test prompt"}],
            score=0.8,
            metric_name="test_metric",
            initial_score=0.6,
            details={"model": "gpt-4", "temperature": 0.7},
        )

        # Test __str__ method
        str_repr = str(result)
        assert "OPTIMIZATION COMPLETE" in str_repr
        assert "TestOptimizer" in str_repr
        assert "0.8000" in str_repr  # Final score
        assert "0.6000" in str_repr  # Initial score
        assert "test_metric" in str_repr

    def test_optimization_result_rich_display(self) -> None:
        """Test that OptimizationResult has proper rich display."""
        result = OptimizationResult(
            optimizer="TestOptimizer",
            prompt=[{"role": "user", "content": "test prompt"}],
            score=0.8,
            metric_name="test_metric",
            initial_score=0.6,
            details={"model": "gpt-4", "temperature": 0.7},
        )

        # Test __rich__ method
        rich_repr = result.__rich__()
        assert rich_repr is not None
        # The rich representation should be a Panel object
        from rich.panel import Panel

        assert isinstance(rich_repr, Panel)

    def test_optimizer_chaining_compatibility(self) -> None:
        """Test that optimizers can be chained together."""
        # This test verifies that the API is compatible with chaining
        optimizers: list[type[Any]] = [
            FewShotBayesianOptimizer,
            GepaOptimizer,
            MetaPromptOptimizer,
            EvolutionaryOptimizer,
        ]

        # Test that all optimizers have the same signature
        signatures = [inspect.signature(opt.optimize_prompt) for opt in optimizers]
        first_sig = signatures[0]

        for i, sig in enumerate(signatures[1:], 1):
            assert sig == first_sig, (
                f"Optimizer {i} has different signature, preventing chaining"
            )

        # Test that all optimizers return OptimizationResult
        for optimizer_class in optimizers:
            sig = inspect.signature(optimizer_class.optimize_prompt)
            assert sig.return_annotation == OptimizationResult, (
                f"{optimizer_class.__name__} doesn't return OptimizationResult, "
                "preventing chaining"
            )

    def test_api_specification_summary(self) -> None:
        """Test that summarizes the API specification compliance."""
        optimizers: list[type[Any]] = [
            FewShotBayesianOptimizer,
            GepaOptimizer,
            MetaPromptOptimizer,
            EvolutionaryOptimizer,
        ]

        # Verify all optimizers exist
        assert len(optimizers) == 4, "Expected 4 optimizers"

        # Verify all have optimize_prompt method
        for optimizer_class in optimizers:
            assert hasattr(optimizer_class, "optimize_prompt"), (
                f"{optimizer_class.__name__} missing optimize_prompt method"
            )

        # Verify all return OptimizationResult
        for optimizer_class in optimizers:
            sig = inspect.signature(optimizer_class.optimize_prompt)
            assert sig.return_annotation == OptimizationResult, (
                f"{optimizer_class.__name__} doesn't return OptimizationResult"
            )

        print("\n" + "=" * 60)
        print("✅ API SPECIFICATION COMPLIANCE VERIFIED")
        print("=" * 60)
        print("✅ All optimizers have identical signatures")
        print("✅ All optimizers return OptimizationResult")
        print("✅ All optimizers have consistent input validation")
        print("✅ All optimizers support serialization")
        print("✅ Optimizers are ready for chaining and UI integration")
        print("=" * 60)

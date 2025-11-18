"""
Unit tests for opik_optimizer package imports and basic functionality.

These tests ensure that:
1. The package can be imported without errors
2. Core components are accessible
3. Basic objects can be created
4. Import times are reasonable
"""

import pytest
import time


def test_basic_package_import() -> None:
    """Test that the main opik_optimizer package can be imported."""
    import opik_optimizer

    # Should have main components accessible
    assert hasattr(opik_optimizer, "MetaPromptOptimizer")
    assert hasattr(opik_optimizer, "EvolutionaryOptimizer")
    assert hasattr(opik_optimizer, "FewShotBayesianOptimizer")
    assert hasattr(opik_optimizer, "OptimizationResult")


def test_import_time_is_reasonable() -> None:
    """Test that imports happen within reasonable time (< 5 seconds)."""
    start_time = time.time()

    import_time = time.time() - start_time

    # Should import in under 5 seconds (generous threshold)
    assert import_time < 5.0, f"Import took {import_time:.2f}s, expected < 5.0s"


def test_optimizer_classes_importable() -> None:
    """Test that all optimizer classes can be imported individually."""
    from opik_optimizer import MetaPromptOptimizer
    from opik_optimizer import EvolutionaryOptimizer
    from opik_optimizer import FewShotBayesianOptimizer

    # Should be callable classes
    assert callable(MetaPromptOptimizer)
    assert callable(EvolutionaryOptimizer)
    assert callable(FewShotBayesianOptimizer)


def test_chat_prompt_import_and_creation() -> None:
    """Test that ChatPrompt can be imported and created."""
    from opik_optimizer import ChatPrompt

    # Test basic creation
    messages = [
        {"role": "system", "content": "You are a helpful assistant."},
        {"role": "user", "content": "Hello, world!"},
    ]

    prompt = ChatPrompt(messages=messages)

    # Validate basic properties
    assert prompt.get_messages() == messages
    assert len(prompt.get_messages()) == 2
    assert prompt.get_messages()[0]["role"] == "system"
    assert prompt.get_messages()[1]["role"] == "user"


def test_optimization_result_import_and_creation() -> None:
    """Test that OptimizationResult can be imported and created with new fields."""
    from opik_optimizer import OptimizationResult

    # Test creation with all required fields including new ones
    initial_prompt = [{"role": "user", "content": "Initial prompt"}]
    optimized_prompt = [{"role": "user", "content": "Optimized prompt"}]

    DATASET_ID = "DATASET-ID"
    OPTIMIZATION_ID = "OPTIMIZATION-ID"

    result = OptimizationResult(
        optimizer="TestOptimizer",
        prompt=optimized_prompt,
        score=0.85,
        initial_prompt=initial_prompt,  # New field
        initial_score=0.70,  # New field
        metric_name="test_metric",
        details={"test": "data"},
        history=[],
        llm_calls=10,
        dataset_id=DATASET_ID,
        optimization_id=OPTIMIZATION_ID,
    )

    # Validate new fields are accessible
    assert result.initial_prompt == initial_prompt
    assert result.initial_score == 0.70
    assert result.score == 0.85
    assert result.optimizer == "TestOptimizer"

    assert result.optimization_id == OPTIMIZATION_ID
    assert result.dataset_id == DATASET_ID

    # Test model_dump includes new fields
    dumped = result.model_dump()
    assert "initial_prompt" in dumped
    assert "initial_score" in dumped


def test_optimizer_initialization_basic() -> None:
    """Test that optimizers can be initialized with basic parameters."""
    from opik_optimizer import MetaPromptOptimizer, EvolutionaryOptimizer
    from opik_optimizer import FewShotBayesianOptimizer

    # Test MetaPromptOptimizer initialization
    meta_optimizer = MetaPromptOptimizer(model="openai/gpt-4", prompts_per_round=2)
    assert meta_optimizer.model == "openai/gpt-4"
    assert meta_optimizer.prompts_per_round == 2

    # Test EvolutionaryOptimizer initialization
    evo_optimizer = EvolutionaryOptimizer(model="openai/gpt-4")
    assert evo_optimizer.model == "openai/gpt-4"

    # Test FewShotBayesianOptimizer initialization
    few_shot_optimizer = FewShotBayesianOptimizer(
        model="openai/gpt-4",
        min_examples=1,
        max_examples=2,
    )
    assert few_shot_optimizer.model == "openai/gpt-4"
    assert few_shot_optimizer.min_examples == 1
    assert few_shot_optimizer.max_examples == 2


if __name__ == "__main__":
    pytest.main([__file__, "-v"])

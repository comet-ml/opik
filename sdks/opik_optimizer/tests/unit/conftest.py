"""
Test configuration for unit tests.

This file provides shared fixtures and configuration for unit tests.
"""

import pytest
from unittest.mock import Mock
from typing import Any
from collections.abc import Callable

from opik import Dataset
from opik.evaluation.metrics.score_result import ScoreResult


@pytest.fixture
def mock_dataset() -> Dataset:
    """Create a mock dataset for testing."""
    dataset = Mock(spec=Dataset)
    dataset.id = "test-dataset-id"
    dataset.get_items.return_value = [
        {"text": "Test input 1", "label": "Test output 1", "id": "1"},
        {"text": "Test input 2", "label": "Test output 2", "id": "2"},
        {"text": "Test input 3", "label": "Test output 3", "id": "3"},
    ]
    return dataset


@pytest.fixture
def mock_metric() -> Callable[[dict[str, Any], str], ScoreResult]:
    """Create a mock metric function."""

    def metric(dataset_item: dict[str, Any], llm_output: str) -> ScoreResult:
        # Simple mock metric that returns a score based on output length
        score = min(len(llm_output) / 100.0, 1.0)
        return ScoreResult(
            value=score,
            name="test_metric",
            reason=f"Mock metric based on output length: {len(llm_output)}",
        )

    return metric


@pytest.fixture
def sample_chat_prompt() -> Any:
    """Create a sample chat prompt for testing."""
    from opik_optimizer.optimization_config import chat_prompt

    return chat_prompt.ChatPrompt(
        system="You are a helpful assistant that provides accurate and concise responses.",
        user="Please answer the following question: {text}",
    )


@pytest.fixture
def sample_messages_prompt() -> Any:
    """Create a sample messages-based prompt for testing."""
    from opik_optimizer.optimization_config import chat_prompt

    return chat_prompt.ChatPrompt(
        messages=[
            {"role": "system", "content": "You are a helpful assistant."},
            {"role": "user", "content": "Please answer: {text}"},
            {"role": "assistant", "content": "I'll help you with that."},
            {"role": "user", "content": "Great! Now please: {text}"},
        ]
    )


@pytest.fixture
def mock_litellm_completion() -> Any:
    """Mock litellm.completion for testing."""
    from unittest.mock import patch

    with patch("litellm.completion") as mock:
        mock.return_value = Mock(
            choices=[Mock(message=Mock(content="Mock LLM response"))]
        )
        yield mock


@pytest.fixture
def mock_litellm_completion_with_json() -> Any:
    """Mock litellm.completion that returns JSON for testing."""
    from unittest.mock import patch

    with patch("litellm.completion") as mock:
        mock.return_value = Mock(
            choices=[
                Mock(
                    message=Mock(
                        content='{"prompts": ["Test prompt 1", "Test prompt 2"]}'
                    )
                )
            ]
        )
        yield mock


@pytest.fixture
def mock_litellm_completion_error() -> Any:
    """Mock litellm.completion that raises an error for testing."""
    from unittest.mock import patch
    from litellm.exceptions import APIError

    with patch("litellm.completion") as mock:
        mock.side_effect = APIError("Mock API error")
        yield mock


@pytest.fixture
def sample_optimization_result() -> Any:
    """Create a sample OptimizationResult for testing."""
    from opik_optimizer.optimization_result import OptimizationResult

    return OptimizationResult(
        optimizer="TestOptimizer",
        prompt=[{"role": "user", "content": "Test prompt"}],
        score=0.85,
        metric_name="test_metric",
        optimization_id="opt-123",
        dataset_id="dataset-456",
        initial_prompt=[{"role": "user", "content": "Initial prompt"}],
        initial_score=0.75,
        details={"rounds": 3, "model": "gpt-4"},
        history=[{"round": 1, "score": 0.8}, {"round": 2, "score": 0.82}],
        llm_calls=150,
    )


@pytest.fixture
def all_optimizer_classes() -> list[type]:
    """Get all optimizer classes for testing."""
    from opik_optimizer import (
        MetaPromptOptimizer,
        EvolutionaryOptimizer,
        FewShotBayesianOptimizer,
        GepaOptimizer,
    )
    from opik_optimizer.mipro_optimizer.mipro_optimizer import MiproOptimizer

    return [
        MetaPromptOptimizer,
        EvolutionaryOptimizer,
        FewShotBayesianOptimizer,
        GepaOptimizer,
        MiproOptimizer,
    ]


@pytest.fixture
def optimizer_instances() -> list[Any]:
    """Create instances of all optimizers for testing."""
    from opik_optimizer import (
        MetaPromptOptimizer,
        EvolutionaryOptimizer,
        FewShotBayesianOptimizer,
        GepaOptimizer,
    )
    from opik_optimizer.mipro_optimizer.mipro_optimizer import MiproOptimizer

    return {
        "MetaPromptOptimizer": MetaPromptOptimizer(
            model="openai/gpt-4", rounds=1, num_prompts_per_round=1
        ),
        "EvolutionaryOptimizer": EvolutionaryOptimizer(
            model="openai/gpt-4", population_size=2, num_generations=1
        ),
        "FewShotBayesianOptimizer": FewShotBayesianOptimizer(
            model="openai/gpt-4", min_examples=1, max_examples=2
        ),
        "GepaOptimizer": GepaOptimizer(model="openai/gpt-4", num_threads=2),
        "MiproOptimizer": MiproOptimizer(model="openai/gpt-4"),
    }


# Test data for parameter validation
VALID_OPTIMIZER_PARAMS = {
    "MetaPromptOptimizer": {
        "model": "openai/gpt-4",
        "rounds": 3,
        "num_prompts_per_round": 4,
        "temperature": 0.7,
        "verbose": 1,
    },
    "EvolutionaryOptimizer": {
        "model": "openai/gpt-4",
        "population_size": 5,
        "num_generations": 10,
        "temperature": 0.5,
        "verbose": 1,
    },
    "FewShotBayesianOptimizer": {
        "model": "openai/gpt-4",
        "min_examples": 2,
        "max_examples": 8,
        "seed": 42,
        "n_threads": 8,
        "verbose": 1,
    },
    "GepaOptimizer": {
        "model": "openai/gpt-4",
        "project_name": "test-project",
        "reflection_model": "openai/gpt-3.5-turbo",
        "num_threads": 6,
        "seed": 42,
        "verbose": 1,
    },
    "MiproOptimizer": {
        "model": "openai/gpt-4",
        "project_name": "test-project",
        "num_threads": 6,
        "verbose": 1,
    },
}


INVALID_OPTIMIZER_PARAMS = {
    "MetaPromptOptimizer": [
        {"model": "openai/gpt-4", "rounds": 0},  # Invalid rounds
        {"model": "openai/gpt-4", "rounds": -1},  # Invalid rounds
        {
            "model": "openai/gpt-4",
            "num_prompts_per_round": 0,
        },  # Invalid prompts per round
    ],
    "EvolutionaryOptimizer": [
        {"model": "openai/gpt-4", "population_size": 0},  # Invalid population size
        {"model": "openai/gpt-4", "num_generations": 0},  # Invalid generations
    ],
    "FewShotBayesianOptimizer": [
        {"model": "openai/gpt-4", "min_examples": 0},  # Invalid min_examples
        {"model": "openai/gpt-4", "max_examples": 0},  # Invalid max_examples
        {"model": "openai/gpt-4", "min_examples": 5, "max_examples": 3},  # min > max
    ],
    "GepaOptimizer": [
        {"model": None},  # Invalid model - None
        {"model": ""},  # Invalid model - empty string
        {"model": "   "},  # Invalid model - whitespace only
        {"model": 123},  # Invalid model - not a string
        {"project_name": 123},  # Invalid project_name - not a string
        {"reflection_model": 123},  # Invalid reflection_model - not a string
        {"verbose": "invalid"},  # Invalid verbose - not an integer
        {"verbose": -1},  # Invalid verbose - negative
        {"seed": "invalid"},  # Invalid seed - not an integer
    ],
    "MiproOptimizer": [
        {"model": None},  # Invalid model
        {"model": "openai/gpt-4", "num_threads": 0},  # Invalid num_threads
        {"model": "openai/gpt-4", "num_threads": -1},  # Invalid num_threads
    ],
}


@pytest.fixture
def valid_optimizer_params() -> dict[str, dict[str, Any]]:
    """Get valid parameters for each optimizer."""
    return VALID_OPTIMIZER_PARAMS


@pytest.fixture
def invalid_optimizer_params() -> dict[str, list[dict[str, Any]]]:
    """Get invalid parameters for each optimizer."""
    return INVALID_OPTIMIZER_PARAMS

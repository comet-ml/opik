import pytest

from opik_optimizer_framework.types import (
    CandidateConfig,
    OptimizationContext,
    OptimizationState,
)


@pytest.fixture
def sample_prompt_messages():
    return [
        {"role": "system", "content": "You are a helpful assistant."},
        {"role": "user", "content": "Summarize: {text}"},
    ]


@pytest.fixture
def sample_candidate_config(sample_prompt_messages):
    return CandidateConfig(
        prompt_messages=sample_prompt_messages,
        model="openai/gpt-4o-mini",
        model_parameters={"temperature": 0.7},
    )


@pytest.fixture
def sample_optimization_context(sample_prompt_messages):
    return OptimizationContext(
        optimization_id="opt-test-123",
        dataset_name="test-dataset",
        prompt_messages=sample_prompt_messages,
        model="openai/gpt-4o-mini",
        model_parameters={"temperature": 0.7},
        metric_type="equals",
        metric_parameters={},
        optimizer_type="SimpleOptimizer",
        optimizer_parameters={},
    )


@pytest.fixture
def sample_optimization_state():
    return OptimizationState()

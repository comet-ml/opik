import pytest

from opik_optimizer_framework.types import (
    OptimizationContext,
    OptimizationState,
)


@pytest.fixture
def sample_candidate_config():
    return {
        "system_prompt": "You are a helpful assistant.",
        "user_message": "Summarize: {text}",
        "model": "openai/gpt-4o-mini",
        "model_parameters": {"temperature": 0.7},
    }


@pytest.fixture
def sample_optimization_context(sample_candidate_config):
    return OptimizationContext(
        optimization_id="opt-test-123",
        dataset_name="test-dataset",
        model="openai/gpt-4o-mini",
        metric_type="equals",
        optimizer_type="GepaOptimizer",
        optimizer_parameters={},
        optimizable_keys=["system_prompt"],
        baseline_config=sample_candidate_config,
    )


@pytest.fixture
def sample_optimization_state():
    return OptimizationState()

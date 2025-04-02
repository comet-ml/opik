import pytest
import json
from typing import Dict, Any

from opik.evaluation.metrics import Hallucination, AnswerRelevance
from opik.guardrail import Guardrail, Transformation, ValidationResult

@pytest.fixture
def metrics():
    return [
        Hallucination(),
        AnswerRelevance()
    ]

@pytest.fixture
def transformations():
    return [
        Transformation(
            transform_fn=lambda x: json.loads(x),
            name="json_parser"
        )
    ]

@pytest.fixture
def guardrail(metrics, transformations):
    return Guardrail(
        metrics=metrics,
        transformations=transformations,
        max_retries=2
    )

@pytest.mark.asyncio
async def test_guardrail_validation(guardrail):
    # Test with valid JSON and content
    result = await guardrail(
        llm_output='{"answer": "This is a valid response"}',
        input_data={"question": "What is the capital of France?"}
    )
    
    assert isinstance(result, ValidationResult)
    assert result.passed
    assert "Hallucination" in result.scores
    assert "AnswerRelevance" in result.scores
    assert isinstance(result.transformed_output, dict)

@pytest.mark.asyncio
async def test_guardrail_transformation(guardrail):
    # Test transformation failure
    with pytest.raises(json.JSONDecodeError):
        await guardrail(
            llm_output="Invalid JSON",
            input_data={"question": "What is the capital of France?"}
        )

@pytest.mark.asyncio
async def test_guardrail_hooks(metrics):
    success_called = False
    failure_called = False
    
    async def on_success(result):
        nonlocal success_called
        success_called = True
    
    async def on_failure(result):
        nonlocal failure_called
        failure_called = True
    
    guardrail = Guardrail(
        metrics=metrics,
        on_success=on_success,
        on_failure=on_failure
    )
    
    # Test success hook
    await guardrail("Valid response")
    assert success_called
    assert not failure_called
    
    # Reset flags
    success_called = False
    failure_called = False
    
    # Test failure hook with invalid content
    await guardrail("Invalid response")
    assert not success_called
    assert failure_called

@pytest.mark.asyncio
async def test_guardrail_history(guardrail):
    # Run multiple validations
    await guardrail('{"answer": "First response"}')
    await guardrail('{"answer": "Second response"}')
    
    assert len(guardrail.history) == 2
    assert all(isinstance(r, ValidationResult) for r in guardrail.history) 
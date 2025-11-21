"""E2E integration tests for _llm_calls module with trace verification."""

import os

import pytest
from typing import TYPE_CHECKING
from pydantic import BaseModel
import litellm

import opik
from opik.integrations import litellm as litellm_integration
import opik_optimizer._llm_calls as _llm_calls

if TYPE_CHECKING:
    from testlib import FakeBackend


class MathResult(BaseModel):
    """Simple Pydantic model for testing."""

    answer: int


@pytest.fixture
def test_model() -> str:
    """Get test model from environment or use default."""
    return os.environ.get("OPIK_TEST_MODEL", "gpt-4o-mini")


@pytest.fixture
def skip_if_no_api_key() -> None:
    """Skip test if no OpenAI API key is available."""
    if not os.environ.get("OPENAI_API_KEY"):
        pytest.skip("OPENAI_API_KEY not set - skipping e2e test")


@pytest.fixture(autouse=True)
def wrap_litellm_with_tracking(monkeypatch: pytest.MonkeyPatch) -> None:
    """Wrap litellm.completion with Opik tracking so fake_backend can capture traces."""
    tracked_completion = litellm_integration.track_completion()(litellm.completion)
    tracked_acompletion = litellm_integration.track_completion()(litellm.acompletion)

    # Patch litellm module globally so _llm_calls.py uses the tracked version
    monkeypatch.setattr("litellm.completion", tracked_completion)
    monkeypatch.setattr("litellm.acompletion", tracked_acompletion)


def test_e2e_native_strategy(
    fake_backend: "FakeBackend", test_model: str, skip_if_no_api_key: None
) -> None:
    """Test call_model with native strategy and verify response_format is used."""
    messages = [
        {"role": "user", "content": "What is 2+2? Respond with just the number."}
    ]

    result = _llm_calls.call_model(
        messages=messages,
        model=test_model,
        response_model=MathResult,
    )

    opik.flush_tracker()

    # Assertions on result
    assert isinstance(result, MathResult)
    assert hasattr(result, "answer")
    assert isinstance(result.answer, int)
    assert result.answer == 4

    # Verify trace was captured
    assert len(fake_backend.trace_trees) == 1
    trace = fake_backend.trace_trees[0]

    # Access the LLM span (not the trace)
    assert len(trace.spans) == 1
    llm_span = trace.spans[0]
    assert llm_span.type == "llm"

    # Verify messages were passed to litellm (check span input, not trace input)
    assert "messages" in llm_span.input
    assert len(llm_span.input["messages"]) == 1
    assert llm_span.input["messages"][0]["content"] == messages[0]["content"]

    # Check that no tools were used (native strategy)
    assert "tools" not in llm_span.input or llm_span.input.get("tools") is None


def test_e2e_tool_call_strategy(
    fake_backend: "FakeBackend", test_model: str, skip_if_no_api_key: None
) -> None:
    """Test call_model with tool_call strategy and verify tools are used."""
    messages = [
        {"role": "user", "content": "What is 2+2? Respond with just the number."}
    ]

    result = _llm_calls.call_model(
        messages=messages,
        model=test_model,
        model_parameters={"response_format_type": "tool_call"},
        response_model=MathResult,
    )

    opik.flush_tracker()

    # Assertions on result
    assert isinstance(result, MathResult)
    assert hasattr(result, "answer")
    assert isinstance(result.answer, int)
    assert result.answer == 4

    # Verify trace was captured
    assert len(fake_backend.trace_trees) == 1
    trace = fake_backend.trace_trees[0]

    # Access the LLM span
    assert len(trace.spans) == 1
    llm_span = trace.spans[0]
    assert llm_span.type == "llm"

    # Verify tools were passed to litellm (check span input)
    assert "tools" in llm_span.input
    assert len(llm_span.input["tools"]) == 1
    assert llm_span.input["tools"][0]["type"] == "function"
    assert llm_span.input["tools"][0]["function"]["name"] == "mandatory_tool_call"
    assert "parameters" in llm_span.input["tools"][0]["function"]

    # Verify tool_choice was set
    assert "tool_choice" in llm_span.input
    assert llm_span.input["tool_choice"]["type"] == "function"
    assert llm_span.input["tool_choice"]["function"]["name"] == "mandatory_tool_call"

    # Verify messages unchanged (no prompt injection)
    assert llm_span.input["messages"][0]["content"] == messages[0]["content"]


def test_e2e_prompt_injection_strategy(
    fake_backend: "FakeBackend", test_model: str, skip_if_no_api_key: None
) -> None:
    """Test call_model with prompt_injection strategy and verify prompt modification."""
    messages = [
        {"role": "user", "content": "What is 2+2? Respond with just the number."}
    ]

    result = _llm_calls.call_model(
        messages=messages,
        model=test_model,
        model_parameters={"response_format_type": "prompt_injection"},
        response_model=MathResult,
    )

    opik.flush_tracker()

    # Assertions on result
    assert isinstance(result, MathResult)
    assert hasattr(result, "answer")
    assert isinstance(result.answer, int)
    assert result.answer == 4

    # Verify trace was captured
    assert len(fake_backend.trace_trees) == 1
    trace = fake_backend.trace_trees[0]

    # Access the LLM span
    assert len(trace.spans) == 1
    llm_span = trace.spans[0]
    assert llm_span.type == "llm"

    # Verify the prompt was modified with format instructions (check span input)
    assert "messages" in llm_span.input
    last_message_content = llm_span.input["messages"][-1]["content"]

    # Check that format instructions were injected
    assert "STRICT OUTPUT FORMAT" in last_message_content
    assert "What is 2+2?" in last_message_content  # Original content preserved
    assert "answer" in last_message_content  # Schema field mentioned
    assert "integer" in last_message_content  # Type mentioned in schema

    # Verify no tools or response_format were used
    assert "tools" not in llm_span.input or llm_span.input.get("tools") is None

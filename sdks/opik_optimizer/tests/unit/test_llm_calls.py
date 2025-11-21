"""Unit tests for _llm_calls module with mocked litellm."""

from typing import Any
from unittest.mock import Mock

import pytest
from pydantic import BaseModel

import opik_optimizer._llm_calls as _llm_calls


class TestResponse(BaseModel):
    """Test Pydantic model for structured outputs."""

    answer: str
    confidence: float


def test_call_model_native_strategy(monkeypatch: pytest.MonkeyPatch) -> None:
    """Test call_model with native (default) response_format strategy."""
    # Prepare test data
    messages = [{"role": "user", "content": "What is 2+2?"}]
    test_response_data = {"answer": "4", "confidence": 1.0}

    # Track what was passed to litellm
    captured_params: dict[str, Any] = {}

    def mock_completion(**kwargs: Any) -> Mock:
        captured_params.update(kwargs)
        # Create mock response
        mock_response = Mock()
        mock_response.choices = [Mock()]
        mock_response.choices[0].message = Mock()
        mock_response.choices[0].message.content = TestResponse(
            **test_response_data
        ).model_dump_json()
        mock_response.choices[0].finish_reason = "stop"
        return mock_response

    monkeypatch.setattr("litellm.completion", mock_completion)

    # Call the function
    result = _llm_calls.call_model(
        messages=messages,
        model="gpt-4o-mini",
        response_model=TestResponse,
    )

    # Assertions
    assert isinstance(result, TestResponse)
    assert result.answer == "4"
    assert result.confidence == 1.0

    # Verify litellm was called with correct parameters
    assert captured_params["model"] == "gpt-4o-mini"
    assert captured_params["messages"] == messages  # Messages unchanged
    assert "response_format" in captured_params
    assert captured_params["response_format"] == TestResponse
    assert "tools" not in captured_params  # No tools for native strategy


def test_call_model_tool_call_strategy(monkeypatch: pytest.MonkeyPatch) -> None:
    """Test call_model with tool_call strategy."""
    # Prepare test data
    messages = [{"role": "user", "content": "What is 2+2?"}]
    test_response_data = {"answer": "4", "confidence": 1.0}

    # Track what was passed to litellm
    captured_params: dict[str, Any] = {}

    def mock_completion(**kwargs: Any) -> Mock:
        captured_params.update(kwargs)
        # Create mock response with tool call
        mock_response = Mock()
        mock_response.choices = [Mock()]
        mock_response.choices[0].message = Mock()
        mock_response.choices[0].message.content = None
        mock_response.choices[0].finish_reason = "tool_calls"

        # Mock tool call structure
        mock_tool_call = Mock()
        mock_tool_call.function = Mock()
        mock_tool_call.function.arguments = TestResponse(
            **test_response_data
        ).model_dump_json()
        mock_response.choices[0].message.tool_calls = [mock_tool_call]

        return mock_response

    monkeypatch.setattr("litellm.completion", mock_completion)

    # Call the function with tool_call strategy
    result = _llm_calls.call_model(
        messages=messages,
        model="gpt-4o-mini",
        model_parameters={"response_format_type": "tool_call"},
        response_model=TestResponse,
    )

    # Assertions
    assert isinstance(result, TestResponse)
    assert result.answer == "4"
    assert result.confidence == 1.0

    # Verify litellm was called with correct parameters
    assert captured_params["model"] == "gpt-4o-mini"
    assert captured_params["messages"] == messages  # Messages unchanged
    assert "tools" in captured_params
    assert len(captured_params["tools"]) == 1
    assert captured_params["tools"][0]["function"]["name"] == "mandatory_tool_call"
    assert "tool_choice" in captured_params
    assert captured_params["tool_choice"]["function"]["name"] == "mandatory_tool_call"
    assert "response_format" not in captured_params  # No response_format for tool_call


def test_call_model_prompt_injection_strategy(monkeypatch: pytest.MonkeyPatch) -> None:
    """Test call_model with prompt_injection strategy."""
    # Prepare test data
    messages = [{"role": "user", "content": "What is 2+2?"}]
    test_response_data = {"answer": "4", "confidence": 1.0}

    # Track what was passed to litellm
    captured_params: dict[str, Any] = {}

    def mock_completion(**kwargs: Any) -> Mock:
        captured_params.update(kwargs)
        # Create mock response
        mock_response = Mock()
        mock_response.choices = [Mock()]
        mock_response.choices[0].message = Mock()
        mock_response.choices[0].message.content = TestResponse(
            **test_response_data
        ).model_dump_json()
        mock_response.choices[0].finish_reason = "stop"
        return mock_response

    monkeypatch.setattr("litellm.completion", mock_completion)

    # Call the function with prompt_injection strategy
    result = _llm_calls.call_model(
        messages=messages,
        model="gpt-4o-mini",
        model_parameters={"response_format_type": "prompt_injection"},
        response_model=TestResponse,
    )

    # Assertions
    assert isinstance(result, TestResponse)
    assert result.answer == "4"
    assert result.confidence == 1.0

    # Verify litellm was called with correct parameters
    assert captured_params["model"] == "gpt-4o-mini"

    # Messages should be modified with format instructions
    passed_messages = captured_params["messages"]
    assert len(passed_messages) == 1
    assert passed_messages[0]["role"] == "user"
    assert "STRICT OUTPUT FORMAT" in passed_messages[0]["content"]
    assert "What is 2+2?" in passed_messages[0]["content"]  # Original content preserved
    assert "answer" in passed_messages[0]["content"]  # Schema included
    assert "confidence" in passed_messages[0]["content"]

    # Verify response_format is NOT passed
    assert "response_format" not in captured_params
    assert "tools" not in captured_params


def test_call_model_invalid_strategy(monkeypatch: pytest.MonkeyPatch) -> None:
    """Test that invalid response_format_type raises ValueError."""
    messages = [{"role": "user", "content": "Test"}]

    with pytest.raises(ValueError) as exc_info:
        _llm_calls.call_model(
            messages=messages,
            model="gpt-4o-mini",
            model_parameters={"response_format_type": "invalid_strategy"},
            response_model=TestResponse,
        )

    assert "Invalid response_format_type" in str(exc_info.value)
    assert "invalid_strategy" in str(exc_info.value)

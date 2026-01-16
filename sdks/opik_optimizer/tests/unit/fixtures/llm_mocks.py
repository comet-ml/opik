"""
LLM mock builders for complex testing scenarios.

This module provides utilities for creating realistic LLM responses
that can be used with the mock_llm_call fixtures.
"""

from typing import Any
from unittest.mock import MagicMock
from pydantic import BaseModel


class LLMResponseBuilder:
    """
    Builder for creating complex LLM response scenarios.

    Useful when you need to mock a sequence of LLM calls with
    specific behaviors or structured outputs.

    Usage:
        builder = LLMResponseBuilder()
        builder.add_response("First response")
        builder.add_response(MyPydanticModel(field="value"))
        builder.add_error(ValueError("Rate limited"))
        builder.add_response("Recovery response")

        responses = builder.build()
        # Use with mock_llm_sequence fixture
    """

    def __init__(self) -> None:
        self._responses: list[Any] = []

    def add_response(self, response: Any) -> "LLMResponseBuilder":
        """Add a successful response to the sequence."""
        self._responses.append(response)
        return self

    def add_error(self, error: Exception) -> "LLMResponseBuilder":
        """Add an error to the sequence (will be raised when called)."""
        self._responses.append(error)
        return self

    def add_structured(
        self, model_class: type[BaseModel], **fields: Any
    ) -> "LLMResponseBuilder":
        """Add a structured Pydantic model response."""
        self._responses.append(model_class(**fields))
        return self

    def build(self) -> list[Any]:
        """Build and return the response sequence."""
        return self._responses.copy()

    def __len__(self) -> int:
        return len(self._responses)


def create_structured_response(
    model_class: type[BaseModel], **fields: Any
) -> BaseModel:
    """
    Create a structured Pydantic model instance for mocking.

    This is a convenience function for creating structured output
    responses that the optimizer expects from LLM calls.

    Usage:
        from opik_optimizer.algorithms.meta_prompt_optimizer.ops.candidate_ops import (
            CandidatePromptsResponse
        )

        response = create_structured_response(
            CandidatePromptsResponse,
            candidates=[{"role": "system", "content": "New prompt"}]
        )
    """
    return model_class(**fields)


def create_chat_completion_response(
    content: str,
    *,
    role: str = "assistant",
    model: str = "gpt-4",
    finish_reason: str = "stop",
    tool_calls: list[dict[str, Any]] | None = None,
) -> MagicMock:
    """
    Create a mock that mimics a LiteLLM ModelResponse.

    Useful for testing code that directly inspects the response object
    rather than just the content.

    Usage:
        response = create_chat_completion_response(
            content="Hello, world!",
            model="gpt-4o"
        )
        assert response.choices[0].message.content == "Hello, world!"
    """
    mock_response = MagicMock()
    mock_response.model = model

    # Create message mock
    mock_message = MagicMock()
    mock_message.role = role
    mock_message.content = content
    mock_message.tool_calls = tool_calls

    # Create choice mock
    mock_choice = MagicMock()
    mock_choice.message = mock_message
    mock_choice.finish_reason = finish_reason
    mock_choice.index = 0

    mock_response.choices = [mock_choice]

    # Usage tracking
    mock_usage = MagicMock()
    mock_usage.prompt_tokens = len(content.split()) * 2  # Rough estimate
    mock_usage.completion_tokens = len(content.split())
    mock_usage.total_tokens = mock_usage.prompt_tokens + mock_usage.completion_tokens
    mock_response.usage = mock_usage

    return mock_response


def create_tool_call_response(
    tool_name: str,
    arguments: dict[str, Any],
    *,
    tool_call_id: str = "call_123",
) -> MagicMock:
    """
    Create a mock response containing a tool call.

    Usage:
        response = create_tool_call_response(
            tool_name="search",
            arguments={"query": "weather in Paris"}
        )
    """
    import json

    tool_call = MagicMock()
    tool_call.id = tool_call_id
    tool_call.type = "function"
    tool_call.function = MagicMock()
    tool_call.function.name = tool_name
    tool_call.function.arguments = json.dumps(arguments)

    return create_chat_completion_response(
        content="",
        tool_calls=[tool_call],
        finish_reason="tool_calls",
    )


class ConversationMocker:
    """
    Helper for mocking multi-turn conversations.

    Tracks conversation history and allows defining responses
    based on conversation context.

    Usage:
        mocker = ConversationMocker()
        mocker.when_contains("hello").respond("Hi there!")
        mocker.when_contains("weather").respond("It's sunny!")
        mocker.default_response("I don't understand.")

        # Use mocker.get_response as side_effect in mock_llm_call
    """

    def __init__(self) -> None:
        self._rules: list[tuple[str, str]] = []
        self._default: str = "Mock response"
        self._history: list[dict[str, Any]] = []

    def when_contains(self, text: str) -> "ConversationMocker._RuleBuilder":
        """Create a rule that triggers when input contains text."""
        return self._RuleBuilder(self, text)

    def default_response(self, response: str) -> "ConversationMocker":
        """Set the default response when no rules match."""
        self._default = response
        return self

    def get_response(self, **kwargs: Any) -> str:
        """
        Get response based on the conversation context.

        Use this as the side_effect in mock_llm_call.
        """
        self._history.append(kwargs)

        messages = kwargs.get("messages", [])
        last_content = ""
        if messages:
            last_msg = messages[-1]
            last_content = str(last_msg.get("content", "")).lower()

        for pattern, response in self._rules:
            if pattern.lower() in last_content:
                return response

        return self._default

    @property
    def call_count(self) -> int:
        """Number of times get_response was called."""
        return len(self._history)

    class _RuleBuilder:
        def __init__(self, parent: "ConversationMocker", pattern: str) -> None:
            self._parent = parent
            self._pattern = pattern

        def respond(self, response: str) -> "ConversationMocker":
            self._parent._rules.append((self._pattern, response))
            return self._parent

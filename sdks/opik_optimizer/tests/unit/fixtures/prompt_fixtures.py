"""Pytest fixtures for common ChatPrompt shapes used across unit tests."""

from __future__ import annotations

import pytest

from opik_optimizer import ChatPrompt
from tests.unit.fixtures import assistant_message, system_message, user_message


@pytest.fixture
def simple_chat_prompt() -> ChatPrompt:
    """Basic ChatPrompt for testing."""
    return ChatPrompt(
        name="test-prompt",
        system="You are a helpful assistant.",
        user="{question}",
    )


@pytest.fixture
def chat_prompt_with_messages() -> ChatPrompt:
    """ChatPrompt using the messages list format."""
    return ChatPrompt(
        name="messages-prompt",
        messages=[
            system_message("You are a helpful assistant."),
            user_message("Hello, {name}!"),
            assistant_message("Hello! How can I help you today?"),
            user_message("{question}"),
        ],
    )


@pytest.fixture
def multimodal_chat_prompt() -> ChatPrompt:
    """ChatPrompt with multimodal content for testing."""
    return ChatPrompt(
        name="multimodal-prompt",
        messages=[
            system_message("Analyze the image."),
            user_message(
                [
                    {"type": "text", "text": "What is in this image?"},
                    {
                        "type": "image_url",
                        "image_url": {"url": "data:image/png;base64,iVBORw0KGgo="},
                    },
                ]
            ),
        ],
    )


@pytest.fixture
def chat_prompt_with_tools() -> ChatPrompt:
    """ChatPrompt with tool definitions for testing."""
    return ChatPrompt(
        name="tool-prompt",
        system="Use the search tool when needed.",
        user="{query}",
        tools=[
            {
                "type": "function",
                "function": {
                    "name": "search",
                    "description": "Search for information on the web",
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "query": {
                                "type": "string",
                                "description": "The search query",
                            }
                        },
                        "required": ["query"],
                    },
                },
            },
            {
                "type": "function",
                "function": {
                    "name": "calculator",
                    "description": "Perform mathematical calculations",
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "expression": {
                                "type": "string",
                                "description": "The math expression to evaluate",
                            }
                        },
                        "required": ["expression"],
                    },
                },
            },
        ],
    )

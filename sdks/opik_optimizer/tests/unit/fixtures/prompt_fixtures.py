"""Pytest fixtures for common ChatPrompt shapes used across unit tests."""

from __future__ import annotations

import pytest

from opik_optimizer import ChatPrompt
from tests.unit.fixtures import system_message, user_message


@pytest.fixture
def simple_chat_prompt() -> ChatPrompt:
    """Basic ChatPrompt for testing."""
    return ChatPrompt(
        name="test-prompt",
        system="You are a helpful assistant.",
        user="{question}",
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

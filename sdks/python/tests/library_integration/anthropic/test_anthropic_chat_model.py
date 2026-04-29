"""Library-integration happy-flow tests for ``AnthropicChatModel``.

Mirrors the litellm/langchain chat-model suites: each test wires the public
``OpikBaseModel`` API against a real Anthropic endpoint and asserts the
``ConversationDict`` shape produced by the new ``generate_chat_completion`` /
``agenerate_chat_completion`` methods.
"""

import json

import pydantic
import pytest

from opik.evaluation.models.anthropic import anthropic_chat_model

pytestmark = pytest.mark.usefixtures("ensure_anthropic_configured")

MODEL_FOR_TESTS = "claude-haiku-4-5"


def test_anthropic_chat_model_generate_chat_completion__happyflow():
    """``generate_chat_completion`` returns a typed assistant ``ConversationDict``."""
    model = anthropic_chat_model.AnthropicChatModel(
        model_name=MODEL_FOR_TESTS, track=False
    )

    message = model.generate_chat_completion(
        messages=[
            {"role": "system", "content": "You answer in one sentence."},
            {"role": "user", "content": "Tell me a short fact about Python."},
        ]
    )

    assert message["role"] == "assistant"
    assert isinstance(message["content"], str)
    assert len(message["content"]) > 0


@pytest.mark.asyncio
async def test_anthropic_chat_model_agenerate_chat_completion__happyflow():
    """Async ``agenerate_chat_completion`` returns the same shape as the sync path."""
    model = anthropic_chat_model.AnthropicChatModel(
        model_name=MODEL_FOR_TESTS, track=False
    )

    message = await model.agenerate_chat_completion(
        messages=[
            {"role": "system", "content": "You answer in one sentence."},
            {"role": "user", "content": "Tell me a short fact about async Python."},
        ]
    )

    assert message["role"] == "assistant"
    assert isinstance(message["content"], str)
    assert len(message["content"]) > 0


def test_anthropic_chat_model_generate_chat_completion__with_response_format():
    """``response_format`` produces JSON content matching the schema."""

    class Answer(pydantic.BaseModel):
        capital: str

    model = anthropic_chat_model.AnthropicChatModel(
        model_name=MODEL_FOR_TESTS, track=False
    )

    message = model.generate_chat_completion(
        messages=[
            {"role": "user", "content": "What is the capital of France?"},
        ],
        response_format=Answer,
    )

    assert message["role"] == "assistant"
    parsed = json.loads(message["content"])
    assert isinstance(parsed["capital"], str)

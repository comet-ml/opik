from typing import Optional


import anthropic
from . import messages_create_decorator
from typing import TypeVar

AnthropicClient = TypeVar(
    "AnthropicClient", anthropic.AsyncAnthropic, anthropic.Anthropic
)


def track_anthropic(
    anthropic_client: AnthropicClient,
    project_name: Optional[str] = None,
) -> AnthropicClient:
    """Adds Opik tracking to an OpenAI client.

    Tracks calls to `openai_client.chat.completions.create()`, it includes support for streaming model.
    Can be used within other Opik-tracked functions.

    Args:
        anthropic_client: An instance of Anthropic client.
        project_name: The name of the project to log data.

    Returns:
        The modified OpenAI client with Opik tracking enabled.
    """
    decorator = messages_create_decorator.AnthropicMessagesCreateDecorator()
    if not hasattr(anthropic_client.messages.create, "opik_tracked"):
        wrapper = decorator.track(
            type="llm",
            name="anthropic_messages_create",
            project_name=project_name,
            metadata={"base_url": anthropic_client.base_url}
        )
        anthropic_client.messages.create = wrapper(anthropic_client.messages.create)

    return anthropic_client

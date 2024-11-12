from typing import Optional
import logging

import anthropic
from . import messages_create_decorator
from . import messages_batch_decorator
from typing import TypeVar

AnthropicClient = TypeVar(
    "AnthropicClient", anthropic.AsyncAnthropic, anthropic.Anthropic
)

LOGGER = logging.getLogger(__name__)


def track_anthropic(
    anthropic_client: AnthropicClient,
    project_name: Optional[str] = None,
) -> AnthropicClient:
    """Adds Opik tracking to an Anthropic client.

    Tracks calls to `Anthropic`'s or `AsynsAnthropic`'s `messages.create()` method.
    Can be used within other Opik-tracked functions.

    Args:
        anthropic_client: An instance of Anthropic client.
        project_name: The name of the project to log data.

    Returns:
        The modified Anthropic client with Opik tracking enabled.
    """

    if hasattr(anthropic_client, "opik_tracked"):
        return anthropic_client

    anthropic_client.opik_tracked = True
    decorator = messages_create_decorator.AnthropicMessagesCreateDecorator()

    create_wrapper = decorator.track(
        type="llm",
        name="anthropic_messages_create",
        project_name=project_name,
        metadata={"base_url": anthropic_client.base_url},
    )
    anthropic_client.messages.create = create_wrapper(anthropic_client.messages.create)

    stream_wrapper = decorator.track(
        type="llm",
        name="anthropic_messages_stream",
        project_name=project_name,
        metadata={"base_url": anthropic_client.base_url},
    )
    anthropic_client.messages.stream = stream_wrapper(anthropic_client.messages.stream)

    batch_create_wrapper = messages_batch_decorator.warning_decorator(
        "At the moment Opik does not support tracking for `client.beta.messages.batches.create` calls",
        LOGGER,
    )
    anthropic_client.beta.messages.batches.create = batch_create_wrapper(
        anthropic_client.beta.messages.batches.create
    )

    return anthropic_client

from typing import Optional
import logging

import anthropic
from . import messages_create_decorator
from . import messages_batch_decorator
from typing import TypeVar, Dict, Any
from opik.types import LLMProvider

AnthropicClient = TypeVar(
    "AnthropicClient",
    anthropic.AsyncAnthropic,
    anthropic.Anthropic,
    anthropic.AsyncAnthropicBedrock,
    anthropic.AnthropicBedrock,
    anthropic.AsyncAnthropicVertex,
    anthropic.AnthropicVertex,
)

LOGGER = logging.getLogger(__name__)


def track_anthropic(
    anthropic_client: AnthropicClient,
    project_name: Optional[str] = None,
) -> AnthropicClient:
    """Adds Opik tracking to an Anthropic client.

    Integrates with the following anthropic library objects:
        * AsyncAnthropic,
        * Anthropic,
        * AsyncAnthropicBedrock,
        * AnthropicBedrock,
        * AsyncAnthropicVertex,
        * AnthropicVertex,

    Supported methods (for all classes above) are:
        * `client.messages.create()`
        * `client.messages.stream()`

    Can be used within other Opik-tracked functions.

    Args:
        anthropic_client: An instance of Anthropic client.
        project_name: The name of the project to log data.

    Returns:
        Anthropic client with integrated Opik tracking logic.
    """

    if hasattr(anthropic_client, "opik_tracked"):
        return anthropic_client

    anthropic_client.opik_tracked = True
    provider = (
        LLMProvider.ANTHROPIC
    )  # TODO: implement a proper support for vertex and bedrock
    decorator_factory = messages_create_decorator.AnthropicMessagesCreateDecorator(
        provider=provider
    )

    metadata = _extract_metadata_from_client(anthropic_client)

    create_decorator = decorator_factory.track(
        type="llm",
        name="anthropic_messages_create",
        project_name=project_name,
        metadata=metadata,
    )
    stream_decorator = decorator_factory.track(
        type="llm",
        name="anthropic_messages_stream",
        project_name=project_name,
        metadata=metadata,
    )
    batch_create_decorator = messages_batch_decorator.warning_decorator(
        "At the moment Opik Anthropic integration does not support tracking for `client.beta.messages.batches.create` calls",
        LOGGER,
    )
    completions_create_decorator = messages_batch_decorator.warning_decorator(
        "Opik Anthropic integration does not support tracking for `client.completions.create` calls",
        LOGGER,
    )

    anthropic_client.messages.create = create_decorator(
        anthropic_client.messages.create
    )
    anthropic_client.messages.stream = stream_decorator(
        anthropic_client.messages.stream
    )
    try:
        anthropic_client.beta.messages.batches.create = batch_create_decorator(
            anthropic_client.beta.messages.batches.create
        )
    except Exception:
        LOGGER.debug(
            "Failed to patch `client.messages.batches.create` method. It is likely because it was not implemented in the provided anthropic client",
            exc_info=True,
        )

    try:
        anthropic_client.completions.create = completions_create_decorator(
            anthropic_client.completions.create
        )
    except Exception:
        LOGGER.debug(
            "Failed to patch `client.completions.create` method. It is likely because it was not implemented in the provided anthropic client",
            exc_info=True,
        )

    return anthropic_client


def _extract_metadata_from_client(client: AnthropicClient) -> Dict[str, Any]:
    metadata = {"base_url": client.base_url}
    if isinstance(
        client, (anthropic.AnthropicBedrock, anthropic.AsyncAnthropicBedrock)
    ):
        metadata["aws_region"] = client.aws_region
    elif isinstance(
        client, (anthropic.AnthropicVertex, anthropic.AsyncAnthropicVertex)
    ):
        metadata["region"] = client.region
        metadata["project_id"] = client.project_id

    return metadata

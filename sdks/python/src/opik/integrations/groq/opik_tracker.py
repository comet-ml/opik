import logging
from typing import Any, Dict, Optional, TypeVar, Union

import groq

from opik.types import LLMProvider

from . import chat_completion_chunks_aggregator, groq_chat_completions_decorator

GroqClient = TypeVar("GroqClient", groq.Groq, groq.AsyncGroq)

LOGGER = logging.getLogger(__name__)


def track_groq(
    groq_client: GroqClient,
    project_name: Optional[str] = None,
    provider: Optional[Union[str, LLMProvider]] = None,
) -> GroqClient:
    """Adds Opik tracking to a Groq client.

    Tracks calls to `groq_client.chat.completions.create()`, including
    `stream=True` mode. Works with both the sync `Groq` and async `AsyncGroq`
    clients, and can be used within other Opik-tracked functions.

    Args:
        groq_client: An instance of Groq or AsyncGroq client.
        project_name: The name of the project to log data.
        provider: The provider name to record on every LLM span. Defaults to
            "groq"; override it when routing the Groq client at an
            OpenAI-compatible endpoint hosted by another provider.

    Returns:
        The Groq client with integrated Opik tracking.
    """
    if hasattr(groq_client, "opik_tracked"):
        return groq_client

    groq_client.opik_tracked = True

    if provider is None:
        resolved_provider = LLMProvider.GROQ.value
    elif isinstance(provider, LLMProvider):
        resolved_provider = provider.value
    else:
        resolved_provider = provider

    decorator_factory = (
        groq_chat_completions_decorator.GroqChatCompletionsTrackDecorator()
    )
    decorator_factory.provider = resolved_provider

    metadata = _extract_metadata_from_client(groq_client)

    create_decorator = decorator_factory.track(
        type="llm",
        name="chat_completion_create",
        generations_aggregator=chat_completion_chunks_aggregator.aggregate,
        project_name=project_name,
        metadata=metadata,
    )

    groq_client.chat.completions.create = create_decorator(
        groq_client.chat.completions.create
    )

    return groq_client


def _extract_metadata_from_client(client: GroqClient) -> Dict[str, Any]:
    return {"base_url": str(client.base_url)}

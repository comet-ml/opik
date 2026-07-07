import logging
from typing import Any, Dict, Optional, TypeVar, Union

import cerebras.cloud.sdk as cerebras

from opik.types import LLMProvider

from . import cerebras_chat_completions_decorator, chat_completion_chunks_aggregator

CerebrasClient = TypeVar("CerebrasClient", cerebras.Cerebras, cerebras.AsyncCerebras)

LOGGER = logging.getLogger(__name__)


def track_cerebras(
    cerebras_client: CerebrasClient,
    project_name: Optional[str] = None,
    provider: Optional[Union[str, LLMProvider]] = None,
) -> CerebrasClient:
    """Adds Opik tracking to a Cerebras client.

    Tracks calls to `cerebras_client.chat.completions.create()`, including
    `stream=True` mode. Works with both the sync `Cerebras` and async
    `AsyncCerebras` clients, and can be used within other Opik-tracked functions.

    Args:
        cerebras_client: An instance of Cerebras or AsyncCerebras client.
        project_name: The name of the project to log data.
        provider: The provider name to record on every LLM span. Defaults to
            "cerebras".

    Returns:
        The Cerebras client with integrated Opik tracking.
    """
    if hasattr(cerebras_client, "opik_tracked"):
        return cerebras_client

    cerebras_client.opik_tracked = True

    if provider is None:
        resolved_provider: str = "cerebras"
    elif isinstance(provider, LLMProvider):
        resolved_provider = provider.value
    else:
        resolved_provider = provider

    decorator_factory = (
        cerebras_chat_completions_decorator.CerebrasChatCompletionsTrackDecorator()
    )
    decorator_factory.provider = resolved_provider

    metadata = _extract_metadata_from_client(cerebras_client)

    create_decorator = decorator_factory.track(
        type="llm",
        name="chat_completion_create",
        generations_aggregator=chat_completion_chunks_aggregator.aggregate,
        project_name=project_name,
        metadata=metadata,
    )

    cerebras_client.chat.completions.create = create_decorator(
        cerebras_client.chat.completions.create
    )

    return cerebras_client


def _extract_metadata_from_client(client: CerebrasClient) -> Dict[str, Any]:
    return {"base_url": str(client.base_url)}

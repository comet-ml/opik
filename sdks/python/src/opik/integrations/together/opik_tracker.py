import logging
from typing import Any, Dict, Optional, TypeVar, Union

import together

from opik.types import LLMProvider

from . import chat_completion_chunks_aggregator, together_chat_completions_decorator

TogetherClient = TypeVar("TogetherClient", together.Together, together.AsyncTogether)

LOGGER = logging.getLogger(__name__)


def track_together(
    together_client: TogetherClient,
    project_name: Optional[str] = None,
    provider: Optional[Union[str, LLMProvider]] = None,
) -> TogetherClient:
    """Adds Opik tracking to a Together AI client.

    Tracks calls to `together_client.chat.completions.create()`, including
    `stream=True` mode. Works with both the sync `Together` and async
    `AsyncTogether` clients, and can be used within other Opik-tracked functions.

    Together's SDK is OpenAI-shaped but defines its own client and stream types,
    so `track_openai` cannot patch it.

    Args:
        together_client: An instance of Together or AsyncTogether client.
        project_name: The name of the project to log data.
        provider: The provider name to record on every LLM span. Defaults to
            "together".

    Returns:
        The Together client with integrated Opik tracking.
    """
    if hasattr(together_client, "opik_tracked"):
        return together_client

    together_client.opik_tracked = True

    if provider is None:
        resolved_provider: str = "together"
    elif isinstance(provider, LLMProvider):
        resolved_provider = provider.value
    else:
        resolved_provider = provider

    decorator_factory = (
        together_chat_completions_decorator.TogetherChatCompletionsTrackDecorator()
    )
    decorator_factory.provider = resolved_provider

    metadata = _extract_metadata_from_client(together_client)

    create_decorator = decorator_factory.track(
        type="llm",
        name="chat_completion_create",
        generations_aggregator=chat_completion_chunks_aggregator.aggregate,
        project_name=project_name,
        metadata=metadata,
    )

    together_client.chat.completions.create = create_decorator(
        together_client.chat.completions.create
    )

    return together_client


def _extract_metadata_from_client(client: TogetherClient) -> Dict[str, Any]:
    # Scheme, host and path only. A caller-supplied base_url may carry credentials
    # in the userinfo, query or fragment, and span metadata reaches the Opik
    # backend; the path is kept because self-hosted deployments route on it.
    url = client.base_url
    host = url.host if url.port is None else f"{url.host}:{url.port}"
    return {"base_url": f"{url.scheme}://{host}{url.path}"}

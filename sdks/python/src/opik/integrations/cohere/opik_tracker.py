import logging
from urllib.parse import urlparse
from typing import Any, Dict, Optional, TypeVar, Union

import cohere

from opik.types import LLMProvider

from . import chat_stream_aggregator, cohere_chat_decorator

CohereClient = TypeVar("CohereClient", cohere.ClientV2, cohere.AsyncClientV2)

LOGGER = logging.getLogger(__name__)


def track_cohere(
    cohere_client: CohereClient,
    project_name: Optional[str] = None,
    provider: Optional[Union[str, LLMProvider]] = None,
) -> CohereClient:
    """Adds Opik tracking to a Cohere client.

    Tracks calls to `cohere_client.chat()` and `cohere_client.chat_stream()`.
    Works with both the sync `ClientV2` and async `AsyncClientV2`, and can be
    used within other Opik-tracked functions.

    Cohere's native API is not OpenAI-shaped -- `chat()` takes keyword-only
    arguments and streaming is a separate `chat_stream()` method returning a
    union of events -- so `track_openai` against Cohere's compatibility
    endpoint does not cover callers using this SDK.

    Args:
        cohere_client: An instance of cohere.ClientV2 or cohere.AsyncClientV2.
        project_name: The name of the project to log data.
        provider: The provider name to record on every LLM span. Defaults to
            "cohere".

    Returns:
        The Cohere client with integrated Opik tracking.
    """
    if hasattr(cohere_client, "opik_tracked"):
        return cohere_client

    cohere_client.opik_tracked = True

    if provider is None:
        resolved_provider: str = "cohere"
    elif isinstance(provider, LLMProvider):
        resolved_provider = provider.value
    else:
        resolved_provider = provider

    decorator_factory = cohere_chat_decorator.CohereChatTrackDecorator()
    decorator_factory.provider = resolved_provider

    metadata = _extract_metadata_from_client(cohere_client)

    chat_decorator = decorator_factory.track(
        type="llm",
        name="chat",
        generations_aggregator=chat_stream_aggregator.aggregate,
        project_name=project_name,
        metadata=metadata,
    )
    chat_stream_decorator = decorator_factory.track(
        type="llm",
        name="chat_stream",
        generations_aggregator=chat_stream_aggregator.aggregate,
        project_name=project_name,
        metadata=metadata,
    )

    cohere_client.chat = chat_decorator(cohere_client.chat)
    cohere_client.chat_stream = chat_stream_decorator(cohere_client.chat_stream)

    return cohere_client


def _extract_metadata_from_client(client: CohereClient) -> Dict[str, Any]:
    # Scheme, host and path only. A caller-supplied base_url may carry
    # credentials in the userinfo, query or fragment, and span metadata reaches
    # the Opik backend; the path is kept because self-hosted and proxied
    # deployments route on it.
    wrapper = getattr(client, "_client_wrapper", None)
    getter = getattr(wrapper, "get_base_url", None) if wrapper else None
    base_url = getter() if callable(getter) else None
    if not base_url:
        return {}

    parsed = urlparse(str(base_url))
    netloc = parsed.hostname or ""
    if parsed.port is not None:
        netloc = f"{netloc}:{parsed.port}"
    return {"base_url": f"{parsed.scheme}://{netloc}{parsed.path}"}

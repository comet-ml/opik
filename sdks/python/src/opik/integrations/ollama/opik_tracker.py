from typing import Optional, TypeVar, Union

import ollama

from opik.types import LLMProvider

from . import chat_chunks_aggregator, ollama_chat_decorator

OllamaClient = TypeVar("OllamaClient", ollama.Client, ollama.AsyncClient)


def track_ollama(
    ollama_client: OllamaClient,
    project_name: Optional[str] = None,
    provider: Optional[Union[str, LLMProvider]] = None,
) -> OllamaClient:
    """Adds Opik tracking to an Ollama client.

    Tracks calls to `ollama_client.chat()`, including `stream=True` mode. Works
    with both the sync `Client` and the async `AsyncClient`, and can be used
    within other Opik-tracked functions.

    Opik's `track_openai` covers Ollama's OpenAI-compatible endpoint; this covers
    the native SDK, whose client and response types are its own.

    Args:
        ollama_client: An instance of ollama.Client or ollama.AsyncClient.
        project_name: The name of the project to log data.
        provider: The provider name to record on every LLM span. Defaults to
            "ollama".

    Returns:
        The Ollama client with integrated Opik tracking.
    """
    if hasattr(ollama_client, "opik_tracked"):
        return ollama_client

    ollama_client.opik_tracked = True

    if provider is None:
        resolved_provider: str = "ollama"
    elif isinstance(provider, LLMProvider):
        resolved_provider = provider.value
    else:
        resolved_provider = provider

    decorator_factory = ollama_chat_decorator.OllamaChatTrackDecorator()
    decorator_factory.provider = resolved_provider

    chat_decorator = decorator_factory.track(
        type="llm",
        name="chat",
        generations_aggregator=chat_chunks_aggregator.aggregate,
        project_name=project_name,
    )

    ollama_client.chat = chat_decorator(ollama_client.chat)

    return ollama_client

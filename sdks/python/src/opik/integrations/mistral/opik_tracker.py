import importlib.metadata
from typing import Optional, Union

from mistralai import Mistral

import opik.semantic_version as semantic_version
from opik.types import LLMProvider

from . import chat_completion_chunks_aggregator, mistral_decorator

# First mistralai release whose public API this integration targets: the
# `EventStream` / `EventStreamAsync` streaming classes were introduced in 1.3.0.
MINIMUM_MISTRALAI_VERSION = "1.3.0"


def _assert_supported_mistralai_version() -> None:
    try:
        installed_version = importlib.metadata.version("mistralai")
    except importlib.metadata.PackageNotFoundError:
        return

    if (
        semantic_version.SemanticVersion.parse(installed_version)
        < MINIMUM_MISTRALAI_VERSION
    ):
        raise RuntimeError(
            f"Opik supports mistralai>={MINIMUM_MISTRALAI_VERSION}, but version "
            f"{installed_version} is installed. Please upgrade with "
            f'`pip install "mistralai>={MINIMUM_MISTRALAI_VERSION},<2"`.'
        )


def track_mistral(
    mistral_client: Mistral,
    project_name: Optional[str] = None,
    provider: Optional[Union[str, LLMProvider]] = None,
) -> Mistral:
    """Adds Opik tracking to a Mistral client.

    The client is always patched; however every wrapped call checks
    ``opik.is_tracing_active()`` before emitting any telemetry. If tracing is
    disabled at call time, the wrapped function executes normally but no
    span/trace is sent.

    Tracks calls to:
    * ``mistral_client.chat.complete()`` and ``chat.complete_async()``
    * ``mistral_client.chat.stream()`` and ``chat.stream_async()``

    Structured-output calls (``chat.parse()`` and its async / streaming
    variants) are tracked too: they delegate to ``complete``/``stream``
    internally, so patching only those primitives yields a single span (named
    ``chat_completion_create`` / ``chat_completion_stream`` after the primitive).

    Can be used within other Opik-tracked functions.

    Args:
        mistral_client: An instance of ``mistralai.Mistral``.
        project_name: The name of the project to log data.
        provider: The provider name to record on every LLM span. Defaults to
            "mistral", which Opik recognizes for cost tracking. Accepts any
            string or an ``opik.LLMProvider`` enum member.

    Returns:
        The modified Mistral client with Opik tracking enabled.
    """
    _assert_supported_mistralai_version()

    if hasattr(mistral_client, "opik_tracked"):
        return mistral_client

    mistral_client.opik_tracked = True

    if provider is None:
        resolved_provider = LLMProvider.MISTRALAI.value
    elif isinstance(provider, LLMProvider):
        resolved_provider = provider.value
    else:
        resolved_provider = provider

    decorator_factory = mistral_decorator.MistralTrackDecorator()
    decorator_factory.provider = resolved_provider

    complete_decorator = decorator_factory.track(
        type="llm",
        name="chat_completion_create",
        generations_aggregator=chat_completion_chunks_aggregator.aggregate,
        project_name=project_name,
    )
    stream_decorator = decorator_factory.track(
        type="llm",
        name="chat_completion_stream",
        generations_aggregator=chat_completion_chunks_aggregator.aggregate,
        project_name=project_name,
    )

    # Only the primitives are patched. ``parse``/``parse_stream`` call these
    # internally, so a parse call produces a single span (no double-logging).
    mistral_client.chat.complete = complete_decorator(mistral_client.chat.complete)
    mistral_client.chat.complete_async = complete_decorator(
        mistral_client.chat.complete_async
    )
    mistral_client.chat.stream = stream_decorator(mistral_client.chat.stream)
    mistral_client.chat.stream_async = stream_decorator(
        mistral_client.chat.stream_async
    )

    return mistral_client

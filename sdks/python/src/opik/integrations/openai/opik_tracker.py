from typing import Optional, TypeVar

import openai

from . import (
    chat_completion_chunks_aggregator,
    openai_chat_completions_decorator,
)
import opik.semantic_version as semantic_version

OpenAIClient = TypeVar("OpenAIClient", openai.OpenAI, openai.AsyncOpenAI)


def track_openai(
    openai_client: OpenAIClient,
    project_name: Optional[str] = None,
) -> OpenAIClient:
    """Adds Opik tracking wrappers to an OpenAI client.

    The client is always patched; however every wrapped call checks
    `opik.decorator.tracing_runtime_config.is_tracing_active()` before emitting
    any telemetry. If tracing is disabled at call time, the wrapped function
    executes normally but no span/trace is sent.

    Tracks calls to:
    * `openai_client.chat.completions.create()`, including support for stream=True mode.
    * `openai_client.beta.chat.completions.parse()`
    * `openai_client.beta.chat.completions.stream()`
    * `openai_client.responses.create()`

    Can be used within other Opik-tracked functions.

    Args:
        openai_client: An instance of OpenAI or AsyncOpenAI client.
        project_name: The name of the project to log data.

    Returns:
        The modified OpenAI client with Opik tracking enabled.
    """
    if hasattr(openai_client, "opik_tracked"):
        return openai_client

    openai_client.opik_tracked = True

    _patch_openai_chat_completions(openai_client, project_name)

    if hasattr(openai_client, "responses"):
        _patch_openai_responses(openai_client, project_name)

    return openai_client


def _patch_openai_chat_completions(
    openai_client: OpenAIClient,
    project_name: Optional[str] = None,
) -> None:
    chat_completions_decorator_factory = (
        openai_chat_completions_decorator.OpenaiChatCompletionsTrackDecorator()
    )
    if openai_client.base_url.host != "api.openai.com":
        chat_completions_decorator_factory.provider = openai_client.base_url.host

    completions_create_decorator = chat_completions_decorator_factory.track(
        type="llm",
        name="chat_completion_create",
        generations_aggregator=chat_completion_chunks_aggregator.aggregate,
        project_name=project_name,
    )
    completions_parse_decorator = chat_completions_decorator_factory.track(
        type="llm",
        name="chat_completion_parse",
        generations_aggregator=chat_completion_chunks_aggregator.aggregate,
        project_name=project_name,
    )
    completions_stream_decorator = chat_completions_decorator_factory.track(
        type="llm",
        name="chat_completion_stream",
        generations_aggregator=chat_completion_chunks_aggregator.aggregate,
        project_name=project_name,
    )

    openai_client.chat.completions.create = completions_create_decorator(
        openai_client.chat.completions.create
    )
    if semantic_version.SemanticVersion.parse(openai.__version__) < "1.92.0":  # type: ignore
        # beta.chat.completions.stream() calls chat.completions.create(stream=True)
        # under the hood.
        # So decorating `create` will automatically work for tracking `stream`.
        openai_client.beta.chat.completions.parse = completions_parse_decorator(
            openai_client.beta.chat.completions.parse
        )
    else:
        # OpenAI reworked beta API.
        # * chat.completion.stream calls chat.completion.create under the hood, so
        #   it doesn't need to be decorated again.
        # * But beta.chat.completion.stream does not call chat.completion.create, so
        #   it needs to be decorated!
        openai_client.beta.chat.completions.stream = completions_stream_decorator(
            openai_client.beta.chat.completions.stream
        )

        openai_client.chat.completions.parse = completions_parse_decorator(
            openai_client.chat.completions.parse
        )
        openai_client.beta.chat.completions.parse = completions_parse_decorator(
            openai_client.beta.chat.completions.parse
        )


def _patch_openai_responses(
    openai_client: OpenAIClient,
    project_name: Optional[str] = None,
) -> None:
    from . import (
        response_events_aggregator,
        openai_responses_decorator,
    )

    responses_decorator_factory = (
        openai_responses_decorator.OpenaiResponsesTrackDecorator()
    )
    if openai_client.base_url.host != "api.openai.com":
        responses_decorator_factory.provider = openai_client.base_url.host

    if hasattr(openai_client.responses, "create"):
        responses_create_decorator = responses_decorator_factory.track(
            type="llm",
            name="responses_create",
            generations_aggregator=response_events_aggregator.aggregate,
            project_name=project_name,
        )
        openai_client.responses.create = responses_create_decorator(
            openai_client.responses.create
        )

    if hasattr(openai_client.responses, "parse"):
        responses_parse_decorator = responses_decorator_factory.track(
            type="llm",
            name="responses_parse",
            generations_aggregator=response_events_aggregator.aggregate,
            project_name=project_name,
        )
        openai_client.responses.parse = responses_parse_decorator(
            openai_client.responses.parse
        )

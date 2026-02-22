from typing import Optional, TypeVar

import openai
import opik

from . import (
    chat_completion_chunks_aggregator,
    openai_chat_completions_decorator,
)
import opik.semantic_version as semantic_version

OpenAIClient = TypeVar("OpenAIClient", openai.OpenAI, openai.AsyncOpenAI)


def _get_provider(openai_client: OpenAIClient) -> str:
    """Get the provider name from the OpenAI client's base URL."""
    if openai_client.base_url.host != "api.openai.com":
        return openai_client.base_url.host
    return "openai"


def track_openai(
    openai_client: OpenAIClient,
    project_name: Optional[str] = None,
) -> OpenAIClient:
    """Adds Opik tracking wrappers to an OpenAI client.

    The client is always patched; however every wrapped call checks
    `opik.is_tracing_active()` before emitting
    any telemetry. If tracing is disabled at call time, the wrapped function
    executes normally but no span/trace is sent.

    Tracks calls to:
    * `openai_client.chat.completions.create()`, including support for stream=True mode.
    * `openai_client.beta.chat.completions.parse()`
    * `openai_client.beta.chat.completions.stream()`
    * `openai_client.responses.create()`
    * `openai_client.videos.create()`, `videos.create_and_poll()`, `videos.poll()`,
      `videos.list()`, `videos.delete()`, `videos.remix()`, `videos.download_content()`,
      and `write_to_file()` on downloaded content
    * `openai_client.audio.speech.create()` - Text-to-Speech generation
    * `openai_client.audio.speech.with_streaming_response.create()` - Streaming TTS

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

    if hasattr(openai_client, "videos"):
        _patch_openai_videos(openai_client, project_name)

    if hasattr(openai_client, "audio"):
        _patch_openai_audio(openai_client, project_name)

    return openai_client


def _patch_openai_chat_completions(
    openai_client: OpenAIClient,
    project_name: Optional[str] = None,
) -> None:
    chat_completions_decorator_factory = (
        openai_chat_completions_decorator.OpenaiChatCompletionsTrackDecorator()
    )
    chat_completions_decorator_factory.provider = _get_provider(openai_client)

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
    responses_decorator_factory.provider = _get_provider(openai_client)

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


def _patch_openai_videos(
    openai_client: OpenAIClient,
    project_name: Optional[str] = None,
) -> None:
    from . import videos

    provider = _get_provider(openai_client)
    create_decorator_factory = videos.VideosCreateTrackDecorator(provider=provider)
    download_decorator_factory = videos.VideosDownloadTrackDecorator()

    video_metadata = {"created_from": "openai", "type": "openai_videos"}
    video_tags = ["openai"]

    if hasattr(openai_client.videos, "create"):
        decorator = create_decorator_factory.track(
            type="llm",
            name="videos.create",
            project_name=project_name,
        )
        openai_client.videos.create = decorator(openai_client.videos.create)

    if hasattr(openai_client.videos, "create_and_poll"):
        decorator = opik.track(
            name="videos.create_and_poll",
            tags=video_tags,
            metadata=video_metadata,
            project_name=project_name,
        )
        openai_client.videos.create_and_poll = decorator(
            openai_client.videos.create_and_poll
        )

    if hasattr(openai_client.videos, "remix"):
        decorator = create_decorator_factory.track(
            type="llm",
            name="videos.remix",
            project_name=project_name,
        )
        openai_client.videos.remix = decorator(openai_client.videos.remix)

    # Note: videos.retrieve is intentionally NOT patched to avoid too many spans
    # since it's called frequently during polling operations.

    if hasattr(openai_client.videos, "poll"):
        decorator = opik.track(
            name="videos.poll",
            tags=video_tags,
            metadata=video_metadata,
            project_name=project_name,
        )
        openai_client.videos.poll = decorator(openai_client.videos.poll)

    if hasattr(openai_client.videos, "delete"):
        decorator = opik.track(
            name="videos.delete",
            tags=video_tags,
            metadata=video_metadata,
            project_name=project_name,
        )
        openai_client.videos.delete = decorator(openai_client.videos.delete)

    # Patch download_content - also patches write_to_file on returned instances
    # download_content returns a lazy response object, write_to_file does the actual download
    if hasattr(openai_client.videos, "download_content"):
        decorator = download_decorator_factory.track(
            type="general",
            name="videos.download_content",
            project_name=project_name,
        )
        openai_client.videos.download_content = decorator(
            openai_client.videos.download_content
        )

    if hasattr(openai_client.videos, "list"):
        decorator = opik.track(
            name="videos.list",
            tags=video_tags,
            metadata=video_metadata,
            project_name=project_name,
        )
        openai_client.videos.list = decorator(openai_client.videos.list)


def _patch_openai_audio(
    openai_client: OpenAIClient,
    project_name: Optional[str] = None,
) -> None:
    """Patch OpenAI audio API methods for tracking.

    Currently supports:
    - audio.speech.create() - Text-to-Speech generation
    - audio.speech.with_streaming_response.create() - Streaming TTS
    """
    from . import openai_tts_decorator

    provider = _get_provider(openai_client)

    # Patch audio.speech if available
    if hasattr(openai_client.audio, "speech"):
        tts_decorator_factory = openai_tts_decorator.OpenaiTTSTrackDecorator(
            provider=provider
        )
        tts_streaming_decorator_factory = (
            openai_tts_decorator.OpenaiTTSStreamingResponseDecorator(provider=provider)
        )

        # Patch audio.speech.create
        if hasattr(openai_client.audio.speech, "create"):
            tts_create_decorator = tts_decorator_factory.track(
                type="llm",
                name="audio.speech.create",
                project_name=project_name,
            )
            openai_client.audio.speech.create = tts_create_decorator(
                openai_client.audio.speech.create
            )

        # Patch audio.speech.with_streaming_response.create
        if hasattr(openai_client.audio.speech, "with_streaming_response"):
            if hasattr(openai_client.audio.speech.with_streaming_response, "create"):
                tts_streaming_create_decorator = tts_streaming_decorator_factory.track(
                    type="llm",
                    name="audio.speech.with_streaming_response.create",
                    project_name=project_name,
                )
                openai_client.audio.speech.with_streaming_response.create = (
                    tts_streaming_create_decorator(
                        openai_client.audio.speech.with_streaming_response.create
                    )
                )

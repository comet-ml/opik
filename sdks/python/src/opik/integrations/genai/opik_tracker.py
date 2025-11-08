from typing import Optional

from google import genai

from . import (
    generate_content_decorator,
    generate_videos_decorator,
    generations_aggregators,
    stream_wrappers,
    encoder_extension,
)


def track_genai(
    client: genai.Client,
    project_name: Optional[str] = None,
    download_video_attachments: bool = True,
) -> genai.Client:
    """
    Adds Opik tracking to an genai.Client.

    Tracks calls to:
    * client.models.generate_content
    * client.models.generate_content_stream
    * client.aio.models.generate_content
    * client.aio.models.generate_content_stream

    Can be used within other Opik-tracked functions.

    Args:
        client: An instance of genai.Client.
        project_name: The name of the project to log data.

    Returns:
        The modified genai.Client with Opik tracking enabled.
    """
    if hasattr(client, "opik_tracked"):
        return client
    encoder_extension.register()

    client.opik_tracked = True

    provider = "google_vertexai" if client.vertexai else "google_ai"

    decorator_factory = generate_content_decorator.GenerateContentTrackDecorator(
        provider=provider
    )

    client.models.generate_content = decorator_factory.track(
        name="generate_content",
        type="llm",
        project_name=project_name,
    )(client.models.generate_content)

    # This conversion is needed so that @track decorator stopped treating generate_content_stream
    # as generator function, as they are tracked slightly differently from what we need here.
    # The user still gets their Iterator object in return.
    client.models.generate_content_stream = (
        stream_wrappers.generator_function_to_normal_function(
            client.models.generate_content_stream
        )
    )
    client.models.generate_content_stream = decorator_factory.track(
        name="generate_content_stream",
        type="llm",
        project_name=project_name,
        generations_aggregator=generations_aggregators.aggregate_response_content_items,
    )(client.models.generate_content_stream)

    client.aio.models.generate_content = decorator_factory.track(
        name="async_generate_content",
        type="llm",
        project_name=project_name,
    )(client.aio.models.generate_content)

    # No need to perform a similar conversion as for the synchronous method, because async version of generate_content_stream
    # is already a wrapper around generator function that works similarly to our helper function
    client.aio.models.generate_content_stream = decorator_factory.track(
        name="async_generate_content_stream",
        type="llm",
        project_name=project_name,
        generations_aggregator=generations_aggregators.aggregate_response_content_items,
    )(client.aio.models.generate_content_stream)

    _patch_generate_videos(client, provider, project_name)
    _patch_operations(
        client,
        provider,
        project_name,
        download_video_attachments=download_video_attachments,
    )

    return client


def _patch_generate_videos(
    client: genai.Client,
    provider: str,
    project_name: Optional[str],
) -> None:
    decorator = generate_videos_decorator.GenerateVideosTrackDecorator(provider=provider)

    if hasattr(client.models, "generate_videos"):
        client.models.generate_videos = decorator.track(
            name="generate_videos",
            type="llm",
            project_name=project_name,
        )(client.models.generate_videos)

    if hasattr(client.aio.models, "generate_videos"):
        client.aio.models.generate_videos = decorator.track(
            name="async_generate_videos",
            type="llm",
            project_name=project_name,
        )(client.aio.models.generate_videos)


def _patch_operations(
    client: genai.Client,
    provider: str,
    project_name: Optional[str],
    download_video_attachments: bool,
) -> None:
    if not hasattr(client, "operations"):
        return

    decorator = generate_videos_decorator.GenerateVideosOperationTracker(
        provider=provider,
        client=client,
        download_video_attachments=download_video_attachments,
    )

    if hasattr(client.operations, "get"):
        client.operations.get = decorator.track(
            name="operations_get",
            type="general",
            project_name=project_name,
        )(client.operations.get)

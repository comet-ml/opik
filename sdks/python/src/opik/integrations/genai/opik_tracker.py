from typing import Callable, Optional

import google.genai as genai
from google.genai import types as genai_types

from . import (
    generate_content_decorator,
    generations_aggregators,
    stream_wrappers,
    encoder_extension,
)


def _get_provider(client: genai.Client) -> str:
    """Get the provider name from the GenAI client."""
    return "google_vertexai" if client.vertexai else "google_ai"


def track_genai(
    client: genai.Client,
    project_name: Optional[str] = None,
    upload_videos: bool = True,
    cost_callback: Optional[
        Callable[[genai_types.GenerateContentResponse], Optional[float]]
    ] = None,
) -> genai.Client:
    """
    Adds Opik tracking to an genai.Client.

    Tracks calls to:
    * client.models.generate_content
    * client.models.generate_content_stream
    * client.aio.models.generate_content
    * client.aio.models.generate_content_stream
    * client.models.generate_videos (Veo video generation)
    * client.aio.models.generate_videos (async Veo video generation)
    * client.operations.get (for polling video generation status)
    * video.save (when saving generated videos)

    Can be used within other Opik-tracked functions.

    Args:
        client: An instance of genai.Client.
        project_name: The name of the project to log data.
        upload_videos: Whether to upload generated videos as attachments
            when video.save is called. Defaults to True.
        cost_callback: An optional callable that receives the GenerateContentResponse
            and returns the cost in USD as a float, or None to use automatic cost
            calculation. Use this for models with complex pricing (e.g., different
            rates for thinking tokens) where automatic cost tracking is insufficient.

    Returns:
        The modified genai.Client with Opik tracking enabled.
    """
    if hasattr(client, "opik_tracked"):
        return client
    encoder_extension.register()

    client.opik_tracked = True

    provider = _get_provider(client)

    _patch_generate_content(client, provider, project_name, cost_callback)
    _patch_generate_videos(client, provider, project_name, upload_videos)

    return client


def _patch_generate_content(
    client: genai.Client,
    provider: str,
    project_name: Optional[str],
    cost_callback: Optional[
        Callable[[genai_types.GenerateContentResponse], Optional[float]]
    ] = None,
) -> None:
    """Patch generate_content methods with Opik tracking."""
    decorator_factory = generate_content_decorator.GenerateContentTrackDecorator(
        provider=provider,
        cost_callback=cost_callback,
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


GENAI_VIDEOS_TAGS = ["genai"]
GENAI_VIDEOS_METADATA = {"created_from": "genai", "type": "genai_videos"}


def _patch_generate_videos(
    client: genai.Client,
    provider: str,
    project_name: Optional[str],
    upload_videos: bool,
) -> None:
    """Patch generate_videos and operations.get methods with Opik tracking."""
    from . import videos

    if not hasattr(client.models, "generate_videos"):
        return

    video_decorator_factory = videos.GenerateVideosTrackDecorator(provider=provider)

    # Patch sync generate_videos
    client.models.generate_videos = video_decorator_factory.track(
        name="models.generate_videos",
        type="llm",
        project_name=project_name,
        tags=GENAI_VIDEOS_TAGS,
        metadata=GENAI_VIDEOS_METADATA,
    )(client.models.generate_videos)

    # Patch async generate_videos
    if hasattr(client.aio.models, "generate_videos"):
        client.aio.models.generate_videos = video_decorator_factory.track(
            name="models.generate_videos",
            type="llm",
            project_name=project_name,
            tags=GENAI_VIDEOS_TAGS,
            metadata=GENAI_VIDEOS_METADATA,
        )(client.aio.models.generate_videos)

    # Patch operations.get to track polling and patch Video.save on completed videos
    _patch_operations_get(client, project_name, upload_videos)


def _patch_operations_get(
    client: genai.Client, project_name: Optional[str], upload_videos: bool
) -> None:
    """Patch operations.get to track polling and patch Video.save on completed videos."""
    from . import videos

    if not hasattr(client, "operations") or not hasattr(client.operations, "get"):
        return

    operations_decorator = videos.OperationsGetTrackDecorator(
        project_name=project_name, upload_videos=upload_videos
    )
    client.operations.get = operations_decorator.track(
        name="operations.get",
        type="general",
        project_name=project_name,
        tags=GENAI_VIDEOS_TAGS,
        metadata=GENAI_VIDEOS_METADATA,
    )(client.operations.get)

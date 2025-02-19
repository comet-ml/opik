from typing import Optional

from google import genai

from . import generate_content_decorator, generations_aggregators


def track_genai(
    client: genai.Client, project_name: Optional[str] = None
) -> genai.Client:
    if hasattr(client, "opik_tracked"):
        return client

    client.opik_tracked = True

    provider = "google_vertexai" if client.vertexai else "google_ai"

    decorator_factory = generate_content_decorator.GenerateContentTrackDecorator(
        provider=provider
    )

    client.models.generate_content = decorator_factory.track(
        name="genai_generate_content",
        type="llm",
        project_name=project_name,
    )(client.models.generate_content)

    client.models.generate_content_stream = decorator_factory.track(
        name="genai_generate_content_stream",
        type="llm",
        project_name=project_name,
        generations_aggregator=generations_aggregators.aggregate_response_content_items,
    )(client.models.generate_content_stream)

    client.aio.models.generate_content = decorator_factory.track(
        name="async_genai_generate_content",
        type="llm",
        project_name=project_name,
    )(client.aio.models.generate_content)

    client.aio.models.generate_content_stream = decorator_factory.track(
        name="async_genai_generate_content_stream",
        type="llm",
        project_name=project_name,
        generations_aggregator=generations_aggregators.aggregate_response_content_items,
    )(client.aio.models.generate_content_stream)

    return client

from typing import Optional, Union

import openai

from . import chat_completion_chunks_aggregator, openai_decorator


def track_openai(
    openai_client: Union[openai.OpenAI, openai.AsyncOpenAI],
    project_name: Optional[str] = None,
) -> Union[openai.OpenAI, openai.AsyncOpenAI]:
    """Adds Opik tracking to an OpenAI client.

    Tracks calls to:
    * `openai_client.chat.completions.create()`, including support for stream=True mode.
    * `openai_client.beta.chat.completions.parse()`
    * `openai_client.beta.chat.completions.stream()`

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

    decorator_factory = openai_decorator.OpenaiTrackDecorator()

    if openai_client.base_url.host != "api.openai.com":
        decorator_factory.provider = openai_client.base_url.host

    completions_create_decorator = decorator_factory.track(
        type="llm",
        name="chat_completion_create",
        generations_aggregator=chat_completion_chunks_aggregator.aggregate,
        project_name=project_name,
    )
    completions_parse_decorator = decorator_factory.track(
        type="llm",
        name="chat_completion_parse",
        generations_aggregator=chat_completion_chunks_aggregator.aggregate,
        project_name=project_name,
    )

    # OpenAI implemented beta.chat.completions.stream() in a way that it
    # calls chat.completions.create(stream=True) under the hood.
    # So decorating `create` will automatically work for tracking `stream`.
    openai_client.chat.completions.create = completions_create_decorator(
        openai_client.chat.completions.create
    )

    openai_client.beta.chat.completions.parse = completions_parse_decorator(
        openai_client.beta.chat.completions.parse
    )

    return openai_client

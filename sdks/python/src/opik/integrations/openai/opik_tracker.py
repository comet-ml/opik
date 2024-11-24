from typing import Optional, Union

import openai

from . import chat_completion_chunks_aggregator, openai_decorator


def track_openai(
    openai_client: Union[openai.OpenAI, openai.AsyncOpenAI],
    project_name: Optional[str] = None,
) -> Union[openai.OpenAI, openai.AsyncOpenAI]:
    """Adds Opik tracking to an OpenAI client.

    Tracks calls to `openai_client.chat.completions.create()`, it includes support for streaming model.
    Can be used within other Opik-tracked functions.

    Args:
        openai_client: An instance of OpenAI or AsyncOpenAI client.
        project_name: The name of the project to log data.

    Returns:
        The modified OpenAI client with Opik tracking enabled.
    """
    decorator_factory = openai_decorator.OpenaiTrackDecorator()
    if not hasattr(openai_client.chat.completions.create, "opik_tracked"):
        completions_create_decorator = decorator_factory.track(
            type="llm",
            name="chat_completion_create",
            generations_aggregator=chat_completion_chunks_aggregator.aggregate,
            project_name=project_name,
        )
        openai_client.chat.completions.create = completions_create_decorator(
            openai_client.chat.completions.create
        )

    return openai_client

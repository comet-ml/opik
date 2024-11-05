from typing import Optional, Union

import openai

from . import openai_decorator, chunks_aggregator


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
    decorator = openai_decorator.OpenaiTrackDecorator()
    if not hasattr(openai_client.chat.completions.create, "opik_tracked"):
        wrapper = decorator.track(
            type="llm",
            name="chat_completion_create",
            generations_aggregator=chunks_aggregator.aggregate,
            project_name=project_name,
        )
        openai_client.chat.completions.create = wrapper(
            openai_client.chat.completions.create
        )

    return openai_client

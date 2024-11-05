from typing import Any, Optional
from . import converse_decorator
from . import chunks_aggregator


def track_bedrock(client: Any, project_name: Optional[str] = None) -> Any:
    """Adds Opik tracking to an AWS Bedrock client.

    Tracks calls to `converse()` and `converse_stream()` methods
    Can be used within other Opik-tracked functions.

    Args:
        client: An instance of an AWS Bedrock client.
        project_name: The name of the project to log data.

    Returns:
        The modified bedrock client with Opik tracking enabled.
    """
    decorator = converse_decorator.BedrockConverseDecorator()

    if not hasattr(client.converse, "opik_tracked"):
        wrapper = decorator.track(
            type="llm",
            name="bedrock_converse",
            project_name=project_name,
        )
        tracked_converse = wrapper(client.converse)
        client.converse = tracked_converse

    if not hasattr(client.converse_stream, "opik_tracked"):
        stream_wrapper = decorator.track(
            type="llm",
            name="bedrock_converse_stream",
            project_name=project_name,
            generations_aggregator=chunks_aggregator.aggregate,
        )
        tracked_converse_stream = stream_wrapper(client.converse_stream)
        client.converse_stream = tracked_converse_stream
    return client

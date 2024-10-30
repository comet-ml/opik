from typing import Any, Optional
from . import converse_decorator
from . import chunks_aggregator


def track_bedrock(client: Any, project_name: Optional[str] = None) -> Any:
    decorator = converse_decorator.BedrockConverseDecorator()
    wrapper = decorator.track(
        type="llm",
        name="bedrock_converse",
        project_name=project_name,
    )
    tracked_converse = wrapper(client.converse)
    client.converse = tracked_converse

    stream_wrapper = decorator.track(
        type="llm",
        name="bedrock_converse_stream",
        project_name=project_name,
        generations_aggregator=chunks_aggregator.aggregate,
    )
    tracked_converse_stream = stream_wrapper(client.converse_stream)
    client.converse_stream = tracked_converse_stream
    return client

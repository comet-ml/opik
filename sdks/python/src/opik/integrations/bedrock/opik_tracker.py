from typing import Any, Optional
from . import converse_decorator


def track_bedrock(client: Any, project_name: Optional[str] = None) -> Any:
    decorator = converse_decorator.BedrockConverseDecorator()
    wrapper = decorator.track(
        type="llm",
        name="bedrock_converse",
        project_name=project_name,
    )
    tracked_converse = wrapper(client.converse)
    client.converse = tracked_converse

    return client

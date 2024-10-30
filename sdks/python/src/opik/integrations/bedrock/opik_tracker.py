from typing import Any, Optional
import opik

def track_bedrock(client, project_name: Optional[str] = None) -> Any:
    tracked_converse = opik.track(name="bedrock_converse", project_name=project_name)(client.converse)
    client.converse = tracked_converse

    return client
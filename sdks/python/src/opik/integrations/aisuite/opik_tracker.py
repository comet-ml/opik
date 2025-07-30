from typing import Optional

import aisuite

from . import aisuite_decorator


def track_aisuite(
    aisuite_client: aisuite.Client,
    project_name: Optional[str] = None,
) -> aisuite.Client:
    """Adds Opik tracking to an AISuite client.

    Tracks calls to:
    * `aisuite_client.chat.completions.create()`,

    Can be used within other Opik-tracked functions.

    Args:
        aisuite_client: An instance of AISuite client.
        project_name: The name of the project to log data.

    Returns:
        The modified AISuite client with Opik tracking enabled.
    """

    if hasattr(aisuite_client, "opik_tracked"):
        return aisuite_client

    aisuite_client.opik_tracked = True

    decorator_factory = aisuite_decorator.AISuiteTrackDecorator()

    completions_create_decorator = decorator_factory.track(
        type="llm",
        name="chat_completion_create",
        project_name=project_name,
    )

    aisuite_client.chat.completions.create = completions_create_decorator(
        aisuite_client.chat.completions.create
    )

    return aisuite_client

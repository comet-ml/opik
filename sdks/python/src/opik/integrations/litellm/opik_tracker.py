from typing import Callable, Optional, TypeVar

from . import litellm_completion_decorator
from . import completion_chunks_aggregator

F = TypeVar("F", bound=Callable)


def track_completion(
    project_name: Optional[str] = None,
) -> Callable[[F], F]:
    """Decorator for tracking LiteLLM function calls with Opik.

    Can be used within other Opik-tracked functions to create proper span hierarchy.

    Supported (streaming and non-streaming modes):
    * `litellm.completion`
    * `litellm.acompletion`

    Example:
        ```python
        import litellm
        from opik.integrations.litellm import track_completion

        tracked_completion = track_completion(project_name="my-project")(litellm.completion)
        response = tracked_completion(model="gpt-3.5-turbo", messages=[...])
        ```

    Args:
        project_name: The name of the project to log data.

    Returns:
        Decorator function that wraps the completion function with Opik tracking.
    """

    decorator_factory = litellm_completion_decorator.LiteLLMCompletionTrackDecorator()

    return decorator_factory.track(  # type: ignore
        type="llm",
        name=None,  # Use the function's name (completion or acompletion)
        project_name=project_name,
        generations_aggregator=completion_chunks_aggregator.aggregate,
    )

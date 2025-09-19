from typing import Optional

from . import litellm_completion_decorator

import litellm


def track_litellm(
    project_name: Optional[str] = None,
) -> None:
    """Adds Opik tracking wrappers to LiteLLM completion functions.

    The functions are always patched; however every wrapped call checks
    `opik.decorator.tracing_runtime_config.is_tracing_active()` before emitting
    any telemetry. If tracing is disabled at call time, the wrapped function
    executes normally but no span/trace is sent.

    Tracks calls to:
    * `litellm.completion()`
    * `litellm.acompletion()`

    Note: Streaming is not currently supported

    Can be used within other Opik-tracked functions.

    Args:
        project_name: The name of the project to log data.

    Returns:
        None - modifies the global litellm module functions.
    """

    if hasattr(litellm, "opik_tracked"):
        return

    litellm.opik_tracked = True

    _patch_litellm_completion(project_name)


def _patch_litellm_completion(
    project_name: Optional[str] = None,
) -> None:
    """Patch LiteLLM completion functions with Opik tracking."""
    decorator_factory = litellm_completion_decorator.LiteLLMCompletionTrackDecorator()

    completion_decorator = decorator_factory.track(
        type="llm",
        name="completion",
        project_name=project_name,
    )
    acompletion_decorator = decorator_factory.track(
        type="llm",
        name="acompletion",
        project_name=project_name,
    )

    # Patch the global functions
    litellm.completion = completion_decorator(litellm.completion)
    litellm.acompletion = acompletion_decorator(litellm.acompletion)

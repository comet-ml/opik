from typing import Optional

from . import litellm_completion_decorator
from . import completion_chunks_aggregator
from . import litellm_opik_logger_compatibility

import litellm


def track_litellm(
    project_name: Optional[str] = None,
) -> None:
    """Adds Opik tracking wrappers to LiteLLM completion functions.

    This function automatically disables LiteLLM's OpikLogger callbacks to prevent
    duplicate logging. The decorator-based integration provides better tracking
    with proper span/trace hierarchy.

    The functions are always patched; however every wrapped call checks
    `opik.decorator.tracing_runtime_config.is_tracing_active()` before emitting
    any telemetry. If tracing is disabled at call time, the wrapped function
    executes normally but no span/trace is sent.

    Tracks calls to:
    * `litellm.completion()` - both streaming and non-streaming
    * `litellm.acompletion()` - both streaming and non-streaming

    Can be used within other Opik-tracked functions.

    Args:
        project_name: The name of the project to log data.

    Returns:
        None - modifies the global litellm module functions.
    """

    if hasattr(litellm, "opik_tracked"):
        return

    litellm.opik_tracked = True

    # Disable OpikLogger callback to prevent duplicate logging
    litellm_opik_logger_compatibility.disable_opik_logger_when_decorator_active()

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
        generations_aggregator=completion_chunks_aggregator.aggregate,
    )
    acompletion_decorator = decorator_factory.track(
        type="llm",
        name="acompletion",
        project_name=project_name,
        generations_aggregator=completion_chunks_aggregator.aggregate,
    )

    # Patch the global functions
    litellm.completion = completion_decorator(litellm.completion)
    litellm.acompletion = acompletion_decorator(litellm.acompletion)

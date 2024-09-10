from typing import TYPE_CHECKING, Optional, Dict, List, Any
from opik.types import UsageDict, DistributedTraceHeadersDict

from . import context_storage, dict_utils, exceptions

if TYPE_CHECKING:
    from .api_objects import trace, span


def get_current_span() -> Optional["span.Span"]:
    """
    Returns current span created by track() decorator or None if no span was found.
    Context-wise.
    """
    if context_storage.span_stack_empty():
        return None

    return context_storage.top_span()


def get_current_trace() -> Optional["trace.Trace"]:
    """
    Returns current trace created by track() decorator or None if no trace was found.
    Context-wise.
    """
    return context_storage.get_trace()


def get_distributed_trace_headers() -> DistributedTraceHeadersDict:
    """
    Returns headers dictionary to be passed into tracked
    function on remote node.
    """
    current_span = context_storage.top_span()
    if current_span is None:
        raise exceptions.OpikException(
            "There is no span in the context to get distributed trace headers from."
        )

    return current_span.get_distributed_trace_headers()


def update_current_span(
    input: Optional[Dict[str, Any]] = None,
    output: Optional[Dict[str, Any]] = None,
    metadata: Optional[Dict[str, Any]] = None,
    tags: Optional[List[str]] = None,
    usage: Optional[UsageDict] = None,
) -> None:
    """
    Updates current span created by track() decorator or raises exception if no span was found.
    Context-wise.
    """
    new_params = dict_utils.remove_none_from_dict(
        {
            "input": input,
            "output": output,
            "metadata": metadata,
            "tags": tags,
            "usage": usage,
        }
    )
    current_span = context_storage.top_span()
    if current_span is None:
        raise exceptions.OpikException("There is no span in the context.")

    current_span.update(**new_params)


def update_current_trace(
    input: Optional[Dict[str, Any]] = None,
    output: Optional[Dict[str, Any]] = None,
    metadata: Optional[Dict[str, Any]] = None,
    tags: Optional[List[str]] = None,
) -> None:
    """
    Updates current trace created by track() decorator or raises exception if no trace was found.
    Context-wise.
    """

    new_params = dict_utils.remove_none_from_dict(
        {
            "input": input,
            "output": output,
            "metadata": metadata,
            "tags": tags,
        }
    )
    current_trace = context_storage.get_trace()

    if current_trace is None:
        raise exceptions.OpikException("There is no trace in the context.")

    current_trace.update(**new_params)


__all__ = [
    "update_current_span",
    "update_current_trace",
]

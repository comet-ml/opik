from typing import TYPE_CHECKING, Optional

from . import context_storage

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


__all__ = [
    "get_current_span",
    "get_current_trace",
]

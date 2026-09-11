import inspect
from collections.abc import Callable


def has_task_span_parameter(func: Callable) -> bool:
    """Check if a scoring function expects the task_span parameter."""
    try:
        sig = inspect.signature(func)
        return "task_span" in sig.parameters
    except (ValueError, TypeError):
        return False

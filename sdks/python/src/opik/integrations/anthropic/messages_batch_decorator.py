import logging
import functools

from typing import Any
from collections.abc import Callable


def warning_decorator(message: str, logger: logging.Logger) -> Callable:
    def decorator(func: Callable) -> Callable:
        @functools.wraps(func)
        def wrapper(*args: Any, **kwargs: Any) -> Any:
            logger.warning(message)
            return func(*args, **kwargs)

        return wrapper

    return decorator

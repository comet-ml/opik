import functools
import logging
from typing import TYPE_CHECKING, Any, Callable

from comet_llm import logging as comet_logging

if TYPE_CHECKING:
    from comet_llm import summary

LOGGER = logging.getLogger(__name__)


def filter(allow_raising: bool, summary: "summary.Summary") -> Callable:
    def decorator(function: Callable) -> Callable:
        @functools.wraps(function)
        def wrapper(*args, **kwargs) -> Any:  # type: ignore
            try:
                return function(*args, **kwargs)
            except Exception as exception:
                summary.increment_failed()

                if allow_raising:
                    raise
                
                if getattr(exception, "log_message_once", False):
                    comet_logging.log_once_at_level(
                        LOGGER,
                        logging.ERROR,
                        str(exception),
                        exc_info=True,
                        extra={"show_traceback": True},
                    )
                else:
                    LOGGER.error(
                        str(exception),
                        exc_info=True,
                        extra={"show_traceback": True},
                    )

        return wrapper

    return decorator

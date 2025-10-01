import logging
import warnings
from typing import Any


_FILTERS_INSTALLED = False


def add_warning_filters() -> None:
    global _FILTERS_INSTALLED
    if _FILTERS_INSTALLED:
        return

    warnings.filterwarnings("ignore", message="coroutine '.*' was never awaited")
    warnings.filterwarnings(
        "ignore",
        message="Enable tracemalloc to get the object allocation traceback",
    )

    class NoEventLoopFilterLiteLLM(logging.Filter):
        def filter(self, record: Any) -> bool:
            return (
                "Asynchronous processing not initialized as we are not running in an async context"
                not in record.getMessage()
            )

    # Add filter to multiple possible loggers
    lite_logger = logging.getLogger("LiteLLM")
    has_filter = any(
        isinstance(f, NoEventLoopFilterLiteLLM) for f in lite_logger.filters
    )
    if not has_filter:
        lite_logger.addFilter(NoEventLoopFilterLiteLLM())

    import litellm

    litellm.suppress_debug_info = True

    _FILTERS_INSTALLED = True

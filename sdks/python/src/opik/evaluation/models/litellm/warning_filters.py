import logging
import warnings
from typing import Any


def add_warning_filters() -> None:
    # TODO: This should be removed when we have fixed the error messages in the LiteLLM library
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
    filter = NoEventLoopFilterLiteLLM()
    logging.getLogger("LiteLLM").addFilter(filter)

    import litellm

    litellm.suppress_debug_info = True  # to disable colorized prints with links to litellm whenever an LLM provider raises an error

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
    warnings.filterwarnings(
        "ignore",
        message=r"(?s)The secret `HF_TOKEN` does not exist in your Colab secrets\..*",
        category=UserWarning,
        module=r"huggingface_hub\.utils\._auth",
    )
    warnings.filterwarnings(
        "ignore",
        category=UserWarning,
        message=r"You are sending unauthenticated requests.*",
    )
    warnings.filterwarnings(
        "ignore",
        category=UserWarning,
        message=r"Some weights of the model checkpoint.*",
    )
    warnings.filterwarnings(
        "ignore",
        category=UserWarning,
        message=r"Some weights of the model checkpoint were not used when initializing.*",
    )
    warnings.filterwarnings(
        "ignore",
        category=UserWarning,
        message=r"Unused arguments passed to model\.forward.*",
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

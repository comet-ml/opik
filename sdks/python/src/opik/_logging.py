from typing import Callable, Any, Optional
import functools
import logging


from . import config

CONSOLE_MSG_FORMAT = "OPIK: %(message)s"
FILE_MSG_FORMAT = "%(asctime)s OPIK %(levelname)s: %(message)s"

# 1MB, to prevent logger from frequent writing hundreds of megabytes in DEBUG mode
# when batches are big and payloads are heavy (e.g. base64 encoded data)
MAX_MESSAGE_LENGTH = 1024 * 1024


class TruncateFormatter(logging.Formatter):
    def __init__(
        self,
        fmt: str,
        datefmt: Optional[str] = None,
        max_length: int = MAX_MESSAGE_LENGTH,
    ) -> None:
        super().__init__(fmt, datefmt)
        self.max_length = max_length

    def format(self, record: logging.LogRecord) -> str:
        result = super().format(record)

        if len(result) > self.max_length:
            result = result[: self.max_length] + "... (truncated)."

        return result


def setup() -> None:
    opik_root_logger = logging.getLogger("opik")
    opik_root_logger.propagate = False

    config_ = config.OpikConfig()

    console_handler = logging.StreamHandler()
    console_level = config_.console_logging_level
    console_handler.setLevel(console_level)
    console_handler.setFormatter(TruncateFormatter(CONSOLE_MSG_FORMAT))
    opik_root_logger.addHandler(console_handler)

    root_level = console_handler.level

    if config_.file_logging_level is not None:
        file_handler = logging.FileHandler(config_.logging_file)
        file_level = config_.file_logging_level
        file_handler.setLevel(file_level)
        file_handler.setFormatter(TruncateFormatter(FILE_MSG_FORMAT))
        opik_root_logger.addHandler(file_handler)

        root_level = min(root_level, file_handler.level)

    opik_root_logger.setLevel(level=root_level)


def convert_exception_to_log_message(
    message: str,
    logger: logging.Logger,
    return_on_exception: Any = None,
    logging_level: int = logging.ERROR,
    **log_kwargs: Any,
) -> Callable:
    def decorator(function: Callable) -> Any:
        @functools.wraps(function)
        def wrapper(*args: Any, **kwargs: Any) -> Any:
            try:
                return function(*args, **kwargs)
            except Exception:
                logger.log(logging_level, message, **log_kwargs)
                return return_on_exception

        return wrapper

    return decorator

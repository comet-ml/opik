import logging
import os

from rich.logging import RichHandler

DEFAULT_LOG_FORMAT = "%(message)s"
DEFAULT_DATE_FORMAT = "%Y-%m-%d %H:%M:%S"

# Store configured state to prevent reconfiguration
_logging_configured = False
_configured_level: int | None = None


def _coerce_level(level: int | str) -> int:
    if isinstance(level, int):
        return level

    normalized = str(level).strip().upper()
    if not normalized:
        return logging.WARNING

    if normalized.isdigit():
        return int(normalized)

    level_value = getattr(logging, normalized, None)
    if isinstance(level_value, int):
        return level_value

    raise ValueError(
        f"Unknown log level '{level}'. Expected standard logging level name or integer."
    )


def setup_logging(
    level: int | str = logging.WARNING,
    format_string: str = DEFAULT_LOG_FORMAT,
    date_format: str = DEFAULT_DATE_FORMAT,
    force: bool = False,
) -> None:
    """
    Configures logging for the opik_optimizer package using rich.

    Args:
        level: The desired logging level (e.g., logging.DEBUG, logging.INFO, logging.WARNING).
        format_string: The format string for log messages.
        date_format: The format string for the date/time in log messages.
        force: If True, reconfigure logging even if already configured.
    """
    env_level = os.getenv("OPIK_LOG_LEVEL")
    target_level = _coerce_level(env_level if env_level is not None else level)

    global _logging_configured, _configured_level
    should_reconfigure = (
        force or not _logging_configured or _configured_level != target_level
    )

    if _logging_configured and not should_reconfigure:
        # Use logger after getting it
        return

    # Configure opik_optimizer package logger
    package_logger = logging.getLogger("opik_optimizer")

    # Avoid adding handlers repeatedly if force=True replaces them
    if not package_logger.handlers or should_reconfigure:
        # Remove existing handlers if forcing re-configuration
        if package_logger.handlers:
            for handler in package_logger.handlers[:]:
                package_logger.removeHandler(handler)

        console_handler = RichHandler(
            rich_tracebacks=True,
            markup=True,  # Enable rich markup in log messages
            log_time_format=f"[{date_format}]",  # Apply date format
        )
        # RichHandler manages formatting, so we don't need a separate formatter
        # formatter = logging.Formatter(format_string, datefmt=date_format)
        # console_handler.setFormatter(formatter)
        package_logger.addHandler(console_handler)

        if format_string:
            formatter = logging.Formatter(format_string, datefmt=date_format)
            console_handler.setFormatter(formatter)

    package_logger.setLevel(target_level)
    package_logger.propagate = False  # Don't duplicate messages in root logger

    # Set levels for noisy libraries like LiteLLM and httpx
    logging.getLogger("LiteLLM").setLevel(logging.WARNING)
    logging.getLogger("urllib3").setLevel(logging.WARNING)
    logging.getLogger("requests").setLevel(logging.WARNING)
    logging.getLogger("httpx").setLevel(logging.WARNING)
    logging.getLogger("dspy").setLevel(logging.WARNING)
    logging.getLogger("datasets").setLevel(logging.WARNING)
    logging.getLogger("optuna").setLevel(logging.WARNING)
    logging.getLogger("filelock").setLevel(logging.WARNING)

    _logging_configured = True
    _configured_level = target_level

    # Use level name provided by rich handler by default
    package_logger.info(
        f"Opik Agent Optimizer logging configured to level: [bold cyan]{logging.getLevelName(target_level)}[/bold cyan]"
    )


# Ensure logger obtained after setup can be used immediately if needed
logger = logging.getLogger(__name__)

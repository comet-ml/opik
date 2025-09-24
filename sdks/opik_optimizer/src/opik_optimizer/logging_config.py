"""Rich logging configuration for opik_optimizer."""

from __future__ import annotations

import logging
import os
from typing import Union

from rich.logging import RichHandler

DEFAULT_LOG_FORMAT = "%(message)s"
DEFAULT_DATE_FORMAT = "%Y-%m-%d %H:%M:%S"

_logging_configured = False
_configured_level: int | None = None


def _coerce_level(level: Union[int, str]) -> int:
    if isinstance(level, int):
        return level

    normalized = str(level).strip().upper()
    if not normalized:
        return logging.WARNING

    if normalized.isdigit():
        return int(normalized)

    if normalized in logging._nameToLevel:  # type: ignore[attr-defined]
        return logging._nameToLevel[normalized]  # type: ignore[attr-defined]

    raise ValueError(
        f"Unknown log level '{level}'. Expected standard logging level name or integer."
    )


def setup_logging(
    level: Union[int, str] = logging.WARNING,
    format_string: str = DEFAULT_LOG_FORMAT,
    date_format: str = DEFAULT_DATE_FORMAT,
    force: bool = False,
) -> None:
    """Configure logging for the opik_optimizer package using Rich."""

    env_level = os.getenv("OPIK_LOG_LEVEL")
    target_level = _coerce_level(env_level if env_level is not None else level)

    global _logging_configured, _configured_level
    should_reconfigure = (
        force or not _logging_configured or _configured_level != target_level
    )

    if _logging_configured and not should_reconfigure:
        return

    package_logger = logging.getLogger("opik_optimizer")

    if not package_logger.handlers or should_reconfigure:
        if package_logger.handlers:
            for handler in package_logger.handlers[:]:
                package_logger.removeHandler(handler)

        console_handler = RichHandler(
            rich_tracebacks=True,
            markup=True,
            log_time_format=f"[{date_format}]",
        )
        if format_string:
            console_handler.setFormatter(
                logging.Formatter(format_string, datefmt=date_format)
            )
        package_logger.addHandler(console_handler)

    package_logger.setLevel(target_level)
    package_logger.propagate = False

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

    package_logger.info(
        "Opik Agent Optimizer logging configured to level: [bold cyan]%s[/bold cyan]",
        logging.getLevelName(target_level),
    )


logger = logging.getLogger(__name__)

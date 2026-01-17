"""Shared logging helpers for the optimizer SDK."""

from __future__ import annotations

import importlib.metadata
import logging
import os
from typing import Any

from rich.console import Console
from rich.logging import RichHandler

from ..constants import OPIK_OPTIMIZER_NO_BANNER_ENV

DEFAULT_LOG_FORMAT = "%(message)s"
DEFAULT_DATE_FORMAT = "%Y-%m-%d %H:%M:%S"
VALID_LOG_LEVELS = {"DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL"}

# Store configured state to prevent reconfiguration
_logging_configured = False
_configured_level: int | None = None


def _coerce_level(level: int | str) -> int:
    """Coerce a log level to an integer."""
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
    level: int | str | None = None,
    format_string: str = DEFAULT_LOG_FORMAT,
    date_format: str = DEFAULT_DATE_FORMAT,
    force: bool = False,
) -> None:
    """Configure the global logging and optional banner."""
    global _logging_configured, _configured_level
    env_level = os.getenv("OPIK_OPTIMIZER_LOG_LEVEL", "").strip().upper()
    if env_level:
        level = env_level
    elif level is None:
        level = logging.WARNING
    if isinstance(level, str):
        normalized = level.strip().upper()
        if (
            normalized
            and normalized not in VALID_LOG_LEVELS
            and not normalized.isdigit()
        ):
            raise ValueError(
                f"Invalid log level '{level}'. Must be one of {sorted(VALID_LOG_LEVELS)}"
            )
    target_level = _coerce_level(level)
    if _logging_configured and not force and _configured_level == target_level:
        return

    console_handler = RichHandler(
        level=target_level,
        markup=True,
        rich_tracebacks=False,
        show_time=True,
        show_level=True,
        show_path=False,
    )

    logging.basicConfig(
        level=target_level,
        format=format_string,
        datefmt=date_format,
        handlers=[console_handler],
        force=True,
    )
    # Align root logger too so module loggers inherit the env level.
    logging.getLogger().setLevel(target_level)
    logging.getLogger("opik_optimizer").setLevel(target_level)

    # Set levels for noisy libraries like LiteLLM and httpx
    logging.getLogger("LiteLLM").setLevel(logging.WARNING)
    logging.getLogger("urllib3").setLevel(logging.WARNING)
    logging.getLogger("requests").setLevel(logging.WARNING)
    logging.getLogger("httpx").setLevel(logging.WARNING)
    logging.getLogger("dspy").setLevel(logging.WARNING)
    logging.getLogger("optuna").setLevel(logging.WARNING)
    logging.getLogger("filelock").setLevel(logging.WARNING)

    # Align Hugging Face/datasets logging style
    for name in ("datasets", "huggingface_hub"):
        hf_logger = logging.getLogger(name)
        for h in list(hf_logger.handlers):
            hf_logger.removeHandler(h)
        hf_logger.addHandler(console_handler)
        hf_logger.setLevel(target_level)
        hf_logger.propagate = False

    _logging_configured = True
    _configured_level = target_level

    # Skip banner for scripts/non-interactive contexts
    show_banner = os.getenv(OPIK_OPTIMIZER_NO_BANNER_ENV, "").lower() not in (
        "1",
        "true",
        "yes",
    )
    if show_banner:
        version = importlib.metadata.version("opik_optimizer")
        banner = (
            "\n[bold cyan]"
            "  ░██████                 ░██    ░██                ░██                               \n"
            " ░██   ░██                ░██                                                         \n"
            "░██     ░██ ░████████  ░████████ ░██░█████████████  ░██░█████████  ░███████  ░██░████ \n"
            "░██     ░██ ░██    ░██    ░██    ░██░██   ░██   ░██ ░██     ░███  ░██    ░██ ░███     \n"
            "░██     ░██ ░██    ░██    ░██    ░██░██   ░██   ░██ ░██   ░███    ░█████████ ░██      \n"
            " ░██   ░██  ░███   ░██    ░██    ░██░██   ░██   ░██ ░██ ░███      ░██        ░██      \n"
            "  ░██████   ░██░█████      ░████ ░██░██   ░██   ░██ ░██░█████████  ░███████  ░██      \n"
            "            ░██                                                                       \n"
            "            ░██                                                                       "
            "[/bold cyan]\n"
            f"Opik Optimizer SDK [bold]Version:[/bold] {version}"
        )
        Console().print(banner)

        logging.getLogger(__name__).info(
            "Opik Agent Optimizer logging configured to level: %s",
            logging.getLevelName(target_level),
        )


def _format_debug_value(value: Any) -> str:
    """Format a debug value for logging."""
    if isinstance(value, float):
        return f"{value:.4f}"
    if isinstance(value, (list, tuple, set)):
        return f"{type(value).__name__}({len(value)})"
    if isinstance(value, dict):
        return f"dict({len(value)})"
    return str(value)


def debug_log(event: str, **fields: Any) -> None:
    """Emit a structured debug line for optimizer events."""
    logger = logging.getLogger("opik_optimizer.debug")
    if not logger.isEnabledFor(logging.DEBUG):
        return
    parts = [f"event={event}"]
    for key, value in fields.items():
        if value is None:
            continue
        parts.append(f"{key}={_format_debug_value(value)}")
    logger.debug(" ".join(parts))


# Ensure logger obtained after setup can be used immediately if needed
logger = logging.getLogger(__name__)

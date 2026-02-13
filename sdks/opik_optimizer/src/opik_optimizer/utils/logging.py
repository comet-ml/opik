"""Shared logging helpers for the optimizer SDK."""

from __future__ import annotations

import importlib.metadata
import logging
import os
from typing import Any
import json

from rich.logging import RichHandler

from ..constants import (
    OPIK_OPTIMIZER_NO_BANNER_ENV,
    DEFAULT_TOOL_DEBUG_CLIP,
    DEFAULT_TOOL_DEBUG_PREFIX,
    DEFAULT_DEBUG_TEXT_CLIP,
)
from .reporting import get_console

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
        console=get_console(),
    )

    base_level = target_level if target_level >= logging.WARNING else logging.WARNING
    logging.basicConfig(
        level=base_level,
        format=format_string,
        datefmt=date_format,
        handlers=[console_handler],
        force=True,
    )
    logging.getLogger("opik_optimizer").setLevel(target_level)
    # Align root logger too so module loggers inherit the env level.
    logging.getLogger().setLevel(target_level)

    # Set levels for noisy libraries to WARNING even when we run at DEBUG.
    for name in (
        "LiteLLM",
        "urllib3",
        "requests",
        "httpx",
        "httpcore",
        "openai",
        "mcp",
        "mcp.client",
        "mcp.client.session",
        "mcp.client.transport",
        "mcp.server",
        "dspy",
        "optuna",
        "filelock",
        "asyncio",
    ):
        logging.getLogger(name).setLevel(logging.WARNING)

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
            "                      ░██   ░██               ░██                             \n"
            "  ░█████   ░███████ ░██████ ░██  ░██████████  ░██ ░███████  ░██████  ░██░████ \n"
            " ░██  ░██  ░██   ░██  ░██   ░██ ░██  ░██  ░██ ░██     ░███ ░██   ░██ ░███     \n"
            "░██    ░██ ░██   ░██  ░██   ░██ ░██  ░██  ░██ ░██   ░███   ░████████ ░██      \n"
            " ░██  ░██  ░██   ░██  ░██   ░██ ░██  ░██  ░██ ░██ ░███     ░██       ░██      \n"
            "  ░█████   ░██░████    ░███ ░██ ░██  ░██  ░██ ░██ ░███████  ░██████  ░██      \n"
            "           ░██                                                                \n"
            "[/bold cyan]\n"
            f"Opik Optimizer SDK [bold]Version:[/bold] {version}"
        )
        get_console().print(banner)

        logging.getLogger(__name__).info(
            "Opik Agent Optimizer logging configured to level: %s",
            logging.getLevelName(target_level),
        )


def _format_debug_value(value: Any) -> str:
    """Format a debug value for logging."""
    if isinstance(value, float):
        return f"{value:.4f}"
    if isinstance(value, dict):
        try:
            return json.dumps(value, ensure_ascii=True, sort_keys=True)
        except Exception:
            return f"dict({len(value)})"
    if isinstance(value, str):
        return json.dumps(value, ensure_ascii=True)
    if isinstance(value, (list, tuple, set)):
        return f"{type(value).__name__}({len(value)})"
    return str(value)


_SECRET_KEY_MARKERS = {
    "api_key",
    "apikey",
    "token",
    "access_token",
    "secret",
    "password",
    "pass",
    "key",
}


def _sanitize_debug_value(value: Any) -> Any:
    """Return a redacted version of the value for debug logs."""
    if isinstance(value, dict):
        sanitized: dict[str, Any] = {}
        for key, val in value.items():
            key_lower = str(key).lower()
            if any(marker in key_lower for marker in _SECRET_KEY_MARKERS):
                sanitized[key] = "<REDACTED>"
            else:
                sanitized[key] = _sanitize_debug_value(val)
        return sanitized
    if isinstance(value, (list, tuple)):
        return [_sanitize_debug_value(item) for item in value]
    if isinstance(value, str):
        lowered = value.lower()
        if any(marker in lowered for marker in _SECRET_KEY_MARKERS):
            return "<REDACTED>"
        return value
    return value


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


def compact_debug_text(text: str, *, limit: int = DEFAULT_DEBUG_TEXT_CLIP) -> str:
    """Collapse whitespace and clip long debug strings."""
    normalized = " ".join(text.split())
    if limit > 0 and len(normalized) > limit:
        return normalized[:limit] + "..."
    return normalized


def debug_tool_call(
    *,
    tool_name: str,
    arguments: dict[str, Any] | None,
    result: Any,
    tool_call_id: str | None = None,
) -> None:
    """Emit a structured debug line for tool execution."""
    logger = logging.getLogger("opik_optimizer.debug")
    if not logger.isEnabledFor(logging.DEBUG):
        return
    arg_text = _format_debug_value(_sanitize_debug_value(arguments))
    if isinstance(arg_text, str) and len(arg_text) > DEFAULT_TOOL_DEBUG_CLIP:
        arg_text = arg_text[:DEFAULT_TOOL_DEBUG_CLIP] + "..."
    result_text = _format_debug_value(_sanitize_debug_value(result))
    if isinstance(result, list) and result:
        preview = _sanitize_debug_value(result[0])
        result_text = _format_debug_value(preview)
    clip_limit = DEFAULT_TOOL_DEBUG_CLIP
    if (
        isinstance(result_text, str)
        and clip_limit > 0
        and len(result_text) > clip_limit
    ):
        result_text = result_text[:clip_limit] + "..."
    call_text = f"{tool_name}({arg_text})"
    call_text = compact_debug_text(call_text, limit=DEFAULT_TOOL_DEBUG_CLIP)
    parts = [f"{DEFAULT_TOOL_DEBUG_PREFIX}event=tool_call", f"call={call_text}"]
    if tool_call_id:
        trimmed_id = tool_call_id.replace("call_", "", 1)
        parts.append(f"call_id={trimmed_id}")
    if isinstance(result_text, str):
        safe_response = compact_debug_text(result_text, limit=DEFAULT_TOOL_DEBUG_CLIP)
        safe_response = safe_response.replace('"', '\\"')
    else:
        safe_response = str(result_text)
    parts.append(f'response="{safe_response}"')
    logger.debug(" ".join(parts))


# Ensure logger obtained after setup can be used immediately if needed
logger = logging.getLogger(__name__)

"""Demo-only logging and display helpers for pytest integration E2E examples."""

from __future__ import annotations

import json
import logging
import os
from typing import Any

from rich.console import Console
from rich.logging import RichHandler
from rich.markup import escape as rich_escape

from opik.simulation import EpisodeResult

DEFAULT_DATE_FORMAT = "%Y-%m-%d %H:%M:%S"
_LOGGING_CONFIGURED = False


def setup_demo_logging() -> None:
    """Configure demo logging once for this demo test package."""
    global _LOGGING_CONFIGURED
    if _LOGGING_CONFIGURED:
        return

    level_name = os.getenv("OPIK_EXAMPLE_LOG_LEVEL", "DEBUG").upper()
    level = getattr(logging, level_name, logging.INFO)

    handler: logging.Handler = RichHandler(
        level=level,
        show_time=True,
        show_level=True,
        show_path=False,
        rich_tracebacks=False,
        markup=True,
        console=Console(stderr=True),
    )

    logging.basicConfig(
        level=level,
        format="%(message)s",
        datefmt=DEFAULT_DATE_FORMAT,
        handlers=[handler],
        force=True,
    )
    logging.getLogger().setLevel(level)

    for logger_name in (
        "opik",
        "opik.api_objects.opik_client",
        "openai",
        "openai._base_client",
        "openai._utils",
        "urllib3",
        "httpx",
        "httpcore",
        "asyncio",
        "LiteLLM",
    ):
        logging.getLogger(logger_name).setLevel(logging.WARNING)

    _LOGGING_CONFIGURED = True


def get_demo_logger(name: str) -> logging.Logger:
    setup_demo_logging()
    return logging.getLogger(name)


def _format_field_value(value: Any) -> str:
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (int, float)):
        return f"{value}"
    if isinstance(value, str):
        return json.dumps(value, ensure_ascii=True)
    if isinstance(value, dict):
        return json.dumps(value, ensure_ascii=True, sort_keys=True)
    if isinstance(value, list):
        return json.dumps(value, ensure_ascii=True)
    return json.dumps(str(value), ensure_ascii=True)


def _normalize_field_name(name: str) -> str:
    normalized = "".join(
        character.lower() if character.isalnum() else "_" for character in name
    ).strip("_")
    while "__" in normalized:
        normalized = normalized.replace("__", "_")
    return normalized


def log_event(
    logger: logging.Logger, event: str, *, level: int = logging.INFO, **fields: Any
) -> None:
    event_text = rich_escape(f"event={event}")
    if event == "episode_summary":
        event_text = f"[cyan]{event_text}[/cyan]"
    elif event in {"episode_assertions_failed", "episode_budgets_failed"}:
        event_text = f"[yellow]{event_text}[/yellow]"
    elif event == "payload":
        event_text = f"[dim]{event_text}[/dim]"

    parts = [event_text]
    for key, value in fields.items():
        if value is None:
            continue
        normalized_key = _normalize_field_name(key)
        if not normalized_key:
            continue
        if normalized_key == "payload" and isinstance(value, str):
            rendered_value = value
        else:
            rendered_value = _format_field_value(value)

        rendered = rich_escape(f"{normalized_key}={rendered_value}")
        if normalized_key == "status":
            value_text = str(value).upper()
            if value_text == "PASS":
                rendered = f"[green]{rendered}[/green]"
            elif value_text == "FAIL":
                rendered = f"[red]{rendered}[/red]"
        elif normalized_key.startswith("failed_"):
            rendered = f"[yellow]{rendered}[/yellow]"
        parts.append(rendered)
    logger.log(level, " ".join(parts))


def log_json_debug(logger: logging.Logger, key: str, payload: Any) -> None:
    """Emit sorted JSON payload at debug level as a structured event."""
    max_chars = int(os.getenv("OPIK_EXAMPLE_DEBUG_PAYLOAD_MAX_CHARS", "4000"))
    rendered = json.dumps(payload, ensure_ascii=True, sort_keys=True)
    if max_chars > 0 and len(rendered) > max_chars:
        rendered = rendered[:max_chars] + "... (truncated)"
    log_event(
        logger,
        "payload",
        level=logging.DEBUG,
        payload_key=key,
        payload=rendered,
    )


def log_conversation_turns_debug(
    logger: logging.Logger, conversation_history: list[dict[str, Any]]
) -> None:
    """Log each conversation turn as a separate debug line."""
    logger.debug("conversation turns:")
    if not conversation_history:
        logger.debug("  (none)")
        return

    for index, message in enumerate(conversation_history, start=1):
        role = message.get("role", "unknown")
        content = str(message.get("content", "")).replace("\n", " ").strip()
        logger.debug("  %02d. %s: %s", index, role, content)


def log_trajectory_steps_debug(
    logger: logging.Logger, trajectory: list[dict[str, Any]]
) -> None:
    """Log each trajectory/tool step as a separate debug line."""
    logger.debug("trajectory steps:")
    if not trajectory:
        logger.debug("  (none)")
        return

    for index, step in enumerate(trajectory, start=1):
        action = step.get("action", "unknown")
        details = step.get("details", {})
        logger.debug("  %02d. action=%s details=%s", index, action, details)


def log_episode_panel(
    logger: logging.Logger,
    *,
    scenario_id: str,
    thread_id: str | None,
    episode: EpisodeResult,
    turns: int | None = None,
    tool_calls: int | None = None,
    extras: dict[str, Any] | None = None,
) -> None:
    """Render a human-readable episode summary at INFO level."""
    assertions_passed = sum(1 for assertion in episode.assertions if assertion.passed)
    assertions_total = len(episode.assertions)
    failed_assertions = [a.name for a in episode.assertions if not a.passed]
    budgets = episode.budgets.all_metrics() if episode.budgets is not None else {}
    budgets_passed = sum(1 for metric in budgets.values() if metric.passed)
    budgets_total = len(budgets)
    failed_budgets = [name for name, metric in budgets.items() if not metric.passed]

    status = "PASS" if episode.is_passing() else "FAIL"
    status_markup = "[green]PASS[/green]" if status == "PASS" else "[red]FAIL[/red]"
    logger.info("episode: %s status=%s", scenario_id, status_markup)
    logger.info(
        "thread=%s turns=%s tool_calls=%s assertions=%s/%s budgets=%s/%s",
        thread_id or "-",
        "-" if turns is None else turns,
        "-" if tool_calls is None else tool_calls,
        assertions_passed,
        assertions_total,
        budgets_passed,
        budgets_total,
    )
    if extras:
        for key, value in extras.items():
            logger.info("%s: %s", key, value)

    if failed_assertions:
        logger.warning("failed assertions: %s", failed_assertions)

    if failed_budgets:
        logger.warning("failed budgets: %s", failed_budgets)

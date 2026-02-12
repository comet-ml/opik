"""Demo-only logging and display helpers for pytest integration E2E examples."""

from __future__ import annotations

import json
import logging
import os
from typing import Any

from opik.simulation import EpisodeResult

DEFAULT_LOG_FORMAT = "%(asctime)s [%(levelname)s] %(name)s: %(message)s"
DEFAULT_DATE_FORMAT = "%Y-%m-%d %H:%M:%S"
_LOGGING_CONFIGURED = False


def setup_demo_logging() -> None:
    """Configure demo logging once for this demo test package."""
    global _LOGGING_CONFIGURED
    if _LOGGING_CONFIGURED:
        return

    level_name = os.getenv("OPIK_EXAMPLE_LOG_LEVEL", "INFO").upper()
    level = getattr(logging, level_name, logging.INFO)
    handler = logging.StreamHandler()
    handler.setLevel(level)

    logging.basicConfig(
        level=level,
        format=DEFAULT_LOG_FORMAT,
        datefmt=DEFAULT_DATE_FORMAT,
        handlers=[handler],
        force=True,
    )
    logging.getLogger().setLevel(level)

    for logger_name in (
        "opik",
        "opik.api_objects.opik_client",
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
        character.lower() if character.isalnum() else "_"
        for character in name
    ).strip("_")
    while "__" in normalized:
        normalized = normalized.replace("__", "_")
    return normalized


def log_event(
    logger: logging.Logger, event: str, *, level: int = logging.INFO, **fields: Any
) -> None:
    parts = [f"event={event}"]
    for key, value in fields.items():
        if value is None:
            continue
        normalized_key = _normalize_field_name(key)
        if not normalized_key:
            continue
        parts.append(f"{normalized_key}={_format_field_value(value)}")
    logger.log(level, " ".join(parts))


def log_json_debug(logger: logging.Logger, key: str, payload: Any) -> None:
    """Emit sorted JSON payload at debug level as a structured event."""
    log_event(logger, "payload", level=logging.DEBUG, payload_key=key, payload=payload)


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
    """Render a compact episode summary."""
    assertions_passed = sum(1 for assertion in episode.assertions if assertion.passed)
    assertions_total = len(episode.assertions)
    failed_assertions = [a.name for a in episode.assertions if not a.passed]
    budgets = episode.budgets.all_metrics() if episode.budgets is not None else {}
    budgets_passed = sum(1 for metric in budgets.values() if metric.passed)
    budgets_total = len(budgets)
    failed_budgets = [name for name, metric in budgets.items() if not metric.passed]

    status = "PASS" if episode.is_passing() else "FAIL"
    log_event(
        logger,
        "episode_summary",
        scenario_id=scenario_id,
        thread_id=thread_id or "-",
        status=status,
        assertions_passed=assertions_passed,
        assertions_total=assertions_total,
        budgets_passed=budgets_passed,
        budgets_total=budgets_total,
        turns=turns,
        tool_calls=tool_calls,
        **(extras or {}),
    )

    if failed_assertions:
        log_event(
            logger,
            "episode_assertions_failed",
            level=logging.WARNING,
            scenario_id=scenario_id,
            failed_assertions=failed_assertions,
        )

    if failed_budgets:
        log_event(
            logger,
            "episode_budgets_failed",
            level=logging.WARNING,
            scenario_id=scenario_id,
            failed_budgets=failed_budgets,
        )

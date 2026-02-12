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


def log_json_debug(logger: logging.Logger, key: str, payload: Any) -> None:
    """Emit sorted JSON payload at debug level."""
    logger.debug("%s=%s", key, json.dumps(payload, indent=2, sort_keys=True))


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
    budgets = episode.budgets.all_metrics() if episode.budgets is not None else {}
    budgets_passed = sum(1 for metric in budgets.values() if metric.passed)
    budgets_total = len(budgets)

    status = "PASS" if episode.is_passing() else "FAIL"
    summary_parts = [
        f"scenario={scenario_id}",
        f"thread={thread_id or '-'}",
        f"status={status}",
        f"assertions={assertions_passed}/{assertions_total}",
        f"budgets={budgets_passed}/{budgets_total}",
    ]
    if turns is not None:
        summary_parts.append(f"turns={turns}")
    if tool_calls is not None:
        summary_parts.append(f"tool_calls={tool_calls}")
    for key, value in (extras or {}).items():
        summary_parts.append(f"{key}={value}")
    logger.info("episode_summary %s", " ".join(summary_parts))

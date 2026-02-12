"""Demo-only logging and display helpers for pytest integration E2E examples."""

from __future__ import annotations

import json
import logging
import os
from typing import Any

from rich import box
from rich.console import Console
from rich.logging import RichHandler
from rich.panel import Panel
from rich.table import Table

from opik.simulation import EpisodeResult

DEFAULT_LOG_FORMAT = "%(message)s"
DEFAULT_DATE_FORMAT = "%Y-%m-%d %H:%M:%S"
_LOGGING_CONFIGURED = False
_CONSOLE: Console | None = None


def _get_console() -> Console:
    global _CONSOLE
    if _CONSOLE is None:
        _CONSOLE = Console()
    return _CONSOLE


def setup_demo_logging() -> None:
    """Configure rich logging once for this demo test package."""
    global _LOGGING_CONFIGURED
    if _LOGGING_CONFIGURED:
        return

    level_name = os.getenv("OPIK_EXAMPLE_LOG_LEVEL", "INFO").upper()
    level = getattr(logging, level_name, logging.INFO)

    handler = RichHandler(
        level=level,
        markup=True,
        rich_tracebacks=False,
        show_time=True,
        show_level=True,
        show_path=False,
        console=_get_console(),
    )
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
    """Render a compact episode summary panel."""
    assertions_passed = sum(1 for assertion in episode.assertions if assertion.passed)
    assertions_total = len(episode.assertions)
    budgets = episode.budgets.all_metrics() if episode.budgets is not None else {}
    budgets_passed = sum(1 for metric in budgets.values() if metric.passed)
    budgets_total = len(budgets)

    status = "PASS" if episode.is_passing() else "FAIL"
    status_style = "green" if episode.is_passing() else "red"

    table = Table(box=box.SIMPLE, show_header=False, pad_edge=False)
    table.add_column("key", style="cyan", no_wrap=True)
    table.add_column("value")
    table.add_row("Scenario", scenario_id)
    table.add_row("Thread", thread_id or "-")
    table.add_row("Status", f"[bold {status_style}]{status}[/bold {status_style}]")
    table.add_row("Assertions", f"{assertions_passed}/{assertions_total}")
    table.add_row("Budgets", f"{budgets_passed}/{budgets_total}")

    if turns is not None:
        table.add_row("Turns", str(turns))
    if tool_calls is not None:
        table.add_row("Tool calls", str(tool_calls))

    for key, value in (extras or {}).items():
        table.add_row(str(key), str(value))

    panel = Panel(
        table,
        title="Episode Summary",
        border_style=status_style,
        box=box.ROUNDED,
    )
    with _get_console().capture() as capture:
        _get_console().print(panel)
    logger.info("\n%s", capture.get().rstrip())

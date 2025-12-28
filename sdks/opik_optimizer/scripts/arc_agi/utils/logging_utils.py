"""Shared Rich console + logging helpers for ARC-AGI scripts."""

from __future__ import annotations

from typing import Any

from rich.console import Console

# Single console instance used across the ARC tooling to keep formatting consistent.
CONSOLE = Console(
    force_terminal=True,
    color_system="truecolor",
    force_jupyter=False,
    soft_wrap=True,
    width=120,
)


def debug_print(message: Any, enabled: bool = True) -> None:
    """Print debug output when ``enabled`` is True."""
    if not enabled:
        return
    CONSOLE.print(message)


__all__ = ["CONSOLE", "debug_print"]

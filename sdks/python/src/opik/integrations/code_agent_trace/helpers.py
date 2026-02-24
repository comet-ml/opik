"""
Helper functions for Code Agent Trace integration.

This module contains utility functions for timestamp parsing,
display formatting, and line counting.
"""

import datetime
import logging
from typing import Any, Dict, List, Optional

from opik import datetime_helpers

from .types import LinesChanged

LOGGER = logging.getLogger(__name__)


def parse_timestamp(timestamp_str: Optional[str]) -> datetime.datetime:
    """
    Parse ISO 8601 timestamp to datetime.

    Args:
        timestamp_str: ISO 8601 timestamp string (e.g., "2024-01-30T08:00:00Z")

    Returns:
        Parsed datetime object, or current time if parsing fails.
    """
    if not timestamp_str:
        return datetime_helpers.local_timestamp()
    try:
        if timestamp_str.endswith("Z"):
            timestamp_str = timestamp_str[:-1] + "+00:00"
        return datetime.datetime.fromisoformat(timestamp_str)
    except (ValueError, TypeError):
        LOGGER.warning(
            "Failed to parse timestamp '%s', using current time", timestamp_str
        )
        return datetime_helpers.local_timestamp()


def calculate_end_time(
    start_time: datetime.datetime, duration_ms: Optional[float]
) -> datetime.datetime:
    """
    Calculate end time from start time and duration.

    Args:
        start_time: Start time as datetime.
        duration_ms: Duration in milliseconds, or None.

    Returns:
        End time as datetime. If duration_ms is None, returns start_time.
    """
    if duration_ms is None:
        return start_time
    return start_time + datetime.timedelta(milliseconds=duration_ms)


def shorten_path(path: str, max_length: int = 40) -> str:
    """
    Shorten file path for display.

    Args:
        path: Full file path.
        max_length: Maximum length for display.

    Returns:
        Shortened path like "dir/.../file.py" or original if short enough.
    """
    if len(path) <= max_length:
        return path
    parts = path.split("/")
    if len(parts) <= 2:
        return path
    return f"{parts[0]}/.../{parts[-1]}"


def shorten_command(command: str, max_length: int = 60) -> str:
    """
    Shorten command for display (removes cd prefix).

    Args:
        command: Full command string.
        max_length: Maximum length for display.

    Returns:
        Shortened command, with cd prefix removed if present.
    """
    if " && " in command:
        command = command.split(" && ")[-1]
    if len(command) <= max_length:
        return command
    return command[: max_length - 3] + "..."


def count_lines_changed(tool_executions: List[Dict[str, Any]]) -> LinesChanged:
    """
    Count lines added and deleted from file edit records.

    Args:
        tool_executions: List of tool execution records with file_edit tool type.

    Returns:
        Dictionary with 'lines_added' and 'lines_deleted' counts.
    """
    lines_added = 0
    lines_deleted = 0

    for record in tool_executions:
        data = record.get("data", {})
        tool_type = data.get("tool_type")
        if tool_type != "file_edit":
            continue

        edits = data.get("edits", [])
        for edit in edits:
            old_str = edit.get("old_string", "")
            new_str = edit.get("new_string", "")

            # Count non-empty lines
            old_lines = (
                len([line for line in old_str.split("\n") if line.strip()])
                if old_str
                else 0
            )
            new_lines = (
                len([line for line in new_str.split("\n") if line.strip()])
                if new_str
                else 0
            )

            lines_deleted += old_lines
            lines_added += new_lines

    return {"lines_added": lines_added, "lines_deleted": lines_deleted}


def format_duration(duration_ms: float) -> str:
    """
    Format duration in milliseconds to a human-readable string.

    Args:
        duration_ms: Duration in milliseconds.

    Returns:
        Formatted duration string like "1.23s" or "123ms".
    """
    if duration_ms < 1000:
        return f"{duration_ms:.2f}ms"
    return f"{duration_ms / 1000:.2f}s"


def format_duration_iso8601(duration_ms: float) -> str:
    """
    Format duration in milliseconds to an ISO 8601 duration string.

    Args:
        duration_ms: Duration in milliseconds.

    Returns:
        Formatted duration string like "PT1.23S" or "PT123MS".
    """
    return f"PT{duration_ms / 1000:.2f}S"

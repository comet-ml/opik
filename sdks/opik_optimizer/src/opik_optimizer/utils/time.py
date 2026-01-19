"""Time utilities for timestamp formatting."""

from __future__ import annotations

import datetime


def now_iso() -> str:
    """Return current UTC time as ISO 8601 string with Z suffix."""
    return (
        datetime.datetime.now(datetime.timezone.utc).isoformat().replace("+00:00", "Z")
    )

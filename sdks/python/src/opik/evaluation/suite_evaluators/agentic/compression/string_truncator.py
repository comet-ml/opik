"""Single-string truncation with `scan`-path suffix.

The suffix tells the judge exactly how to retrieve the un-truncated
value via the `scan` tool. Wording diverges from the backend's `jq`
suffix on purpose — see design doc §3.5.
"""

from typing import Optional

TRUNCATED_SUFFIX_TEMPLATE = "[TRUNCATED {n} chars — use scan('{path}') to see full]"


def truncate(value: str, limit: int, scan_path: Optional[str]) -> str:
    """Truncate `value` to `limit` chars, appending a `scan`-path hint.

    Returns the value unchanged when it's already within the limit.
    When truncated, the head is kept and the suffix announces the
    dropped count plus the path the judge can hand to `scan` to fetch
    the original. `scan_path` may be None for the root value, in which
    case the hint embeds `.` (the jq root expression).
    """
    if len(value) <= limit:
        return value
    dropped = len(value) - limit
    path = scan_path if scan_path else "."
    return value[:limit] + TRUNCATED_SUFFIX_TEMPLATE.format(n=f"{dropped:,}", path=path)

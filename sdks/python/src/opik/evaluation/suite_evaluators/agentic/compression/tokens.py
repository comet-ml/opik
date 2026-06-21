"""Cheap token estimator used by every compressor to pick a tier.

Matches the backend (`String.length() / 4`) so the same trace produces
the same tier decisions on both sides. Accurate token counts are not
needed here — we only need a stable, monotonic proxy that's fast to
compute on every candidate payload.

Caveat (carried over from the design doc §10): Python `len()` counts
Unicode code points, Java `String.length()` counts UTF-16 code units.
The two diverge on surrogate pairs (emoji, some CJK extension B+).
Tracked as a regression item; behaviorally harmless for tier selection
on typical content.
"""

import json
from typing import Any


def estimate_tokens(payload: Any) -> int:
    """Estimate token count for an arbitrary JSON-shaped payload.

    Strings are measured directly; non-strings are first rendered to
    JSON (with `default=str` to tolerate datetime-like values) and
    measured. The 4-chars-per-token heuristic comes straight from the
    backend.
    """
    if isinstance(payload, str):
        text = payload
    else:
        try:
            text = json.dumps(payload, default=str)
        except (TypeError, ValueError):
            text = str(payload)
    return len(text) // 4

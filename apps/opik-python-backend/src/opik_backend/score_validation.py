"""Validation of the score results a user metric returned, before they are handed to the backend.

``ScoreResult.value`` is declared ``float`` in the SDK, so a metric returning ``None`` for it already
breaks that contract; nothing enforces it at runtime. A score with no value cannot be stored — the
backend's ``feedback_scores.value`` column is not nullable — and a score the metric itself flagged as
failed carries a placeholder ``0.0`` that would otherwise be stored as if it were a real zero.

The endpoint rejects a response only when *no* score is usable: a mixed list is passed through, so the
usable scores still reach the backend, which drops the rest and reports them on the rule's log stream.

``scoring_failed`` is matched strictly against ``True`` rather than by truthiness, because this side only
decides whether to reject the whole response while the backend decides what is stored. A truthy
non-boolean (``"false"`` is a truthy string in Python) would otherwise reject a score the backend would
have stored quite happily. Erring the other way costs nothing: the backend deserializes the flag itself
and drops what it considers failed.
"""

import re
from typing import Any, Dict, List, Tuple

# Score names come from user code and land in an error message that the backend writes to the rule's
# user-facing log, so a newline must not be able to forge an entry there, nor a huge name flood one.
_CONTROL_CHARS = re.compile(r"[\x00-\x1f\x7f]")
_MAX_NAME_CHARS = 100
_MAX_REPORTED_NAMES = 10

NO_VALUE = "returned no value"
SCORING_FAILED = "reported the scoring as failed"


def unusable_scores(scores: List[Dict[str, Any]]) -> List[Tuple[str, str]]:
    """Return ``(name, reason)`` for every score that cannot be stored, in the order given."""
    unusable = []
    for score in scores:
        if not isinstance(score, dict):
            unusable.append(("", NO_VALUE))
        elif score.get("scoring_failed") is True:
            # Checked before the value, so a metric that both failed and returned nothing is reported by
            # its cause rather than the symptom. One reason per score keeps the message readable.
            unusable.append((score.get("name"), SCORING_FAILED))
        elif score.get("value") is None:
            unusable.append((score.get("name"), NO_VALUE))
    return unusable


def describe_unusable(unusable: List[Tuple[str, str]]) -> str:
    """Render the offending scores for an error message: name plus why, capped and sanitized."""
    shown = [
        f"'{_sanitize(name)}' {reason}"
        for name, reason in unusable[:_MAX_REPORTED_NAMES]
    ]
    omitted = len(unusable) - len(shown)
    rendered = ", ".join(shown)
    return rendered if omitted == 0 else f"{rendered} and {omitted:,} more"


def _sanitize(name: Any) -> str:
    if name is None or name == "":
        return "<unnamed>"
    stripped = _CONTROL_CHARS.sub(" ", str(name))
    return stripped if len(stripped) <= _MAX_NAME_CHARS else f"{stripped[:_MAX_NAME_CHARS]}…"

"""Whether the score results a user metric returned can be stored by the backend.

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

from typing import Any, Dict, List, Optional


def has_usable_score(scores: List[Dict[str, Any]]) -> bool:
    """Whether at least one of these score results is one the backend can store."""
    return any(_is_usable(score) for score in scores)


def rejection_reason(response: Any) -> Optional[str]:
    """Why ``evaluator.execute_evaluator_python`` would reject this response.

    Returns ``None`` when the response is servable. The labels are the same ones
    used for the execution-outcome telemetry, so the counter cannot disagree with
    the HTTP layer: ``error_payload`` (the metric returned an error), ``no_scores``
    (no ``scores`` list, an empty one, or a null/non-list value), and
    ``no_usable_score`` (scores are present but none can be stored).
    """
    if not isinstance(response, dict):
        return "unparsable_result"

    if "error" in response:
        return "error_payload"

    scores = response.get("scores")
    if not isinstance(scores, list) or len(scores) == 0:
        return "no_scores"

    if not has_usable_score(scores):
        return "no_usable_score"

    return None


def _is_usable(score: Any) -> bool:
    if not isinstance(score, dict):
        return False
    if score.get("scoring_failed") is True:
        return False
    return score.get("value") is not None

"""
Score-comparison utilities shared across optimizers.

This is the shared helper for the "does this candidate beat the baseline / the
running best?" decision. Routing baseline/winner comparisons through it keeps the
tie policy explicit in one place. Note: not every inline score comparison in the
codebase is migrated here (some remain intentionally, e.g. convergence-threshold
and display-only comparisons), so this is the canonical helper for the tie
policy, not a claim that no `>`/`<=` exists elsewhere.
"""

from __future__ import annotations


def improves_over(score: float | None, baseline: float | None) -> bool:
    """Return True only if ``score`` STRICTLY beats ``baseline`` (higher is better).

    Tie policy (OPIK-7038): an exact tie does NOT beat the baseline, so the
    original/seed prompt is kept. A candidate that merely matches the baseline
    score is not considered an improvement.

    ``None`` on either side means "no comparable value" and yields ``False`` (a
    missing score never counts as an improvement). ``NaN`` also yields ``False``
    (``NaN > x`` is ``False``); callers that replaced a raw ``score <= baseline``
    check with ``not improves_over(...)`` therefore fall back to the seed on a
    ``NaN``/``None`` score instead of crashing or picking it as a winner.
    """
    if score is None or baseline is None:
        return False
    return score > baseline

"""Bespoke adaptive compressor for traces.

Three tiers, picked by token estimate against the FULL composite:
- FULL — full `{"trace": ..., "spans": [...]}` untouched.
- MEDIUM — same composite, but every string longer than
  `STRING_TRUNCATION_LENGTH` chars replaced via path-aware truncator.
- SKELETON — identity fields + counts + a minimal span tree (id /
  name / type, with parent/child structure).

Backend parity: thresholds and skeleton shape match
`TraceCompressor.java`. Suffix wording diverges (we point at `scan`,
not `jq`) — see design doc §3.5.
"""

import datetime
import logging
from typing import Any, Dict, List, Optional

from . import path_aware_truncator, tier as tier_module, tokens

LOGGER = logging.getLogger(__name__)

FULL_TOKEN_LIMIT = 8_000
MEDIUM_TOKEN_LIMIT = 50_000
STRING_TRUNCATION_LENGTH = 1_000


def build_full_json(
    trace: Dict[str, Any], spans: List[Dict[str, Any]]
) -> Dict[str, Any]:
    """Compose the FULL view `{"trace": trace, "spans": spans}`.

    Caller passes already-serialized dicts (see `context._serialize_trace`
    / `_serialize_span`); we don't reach back into the typed models, so
    this stays pure.
    """
    return {"trace": trace, "spans": spans}


def compress(
    full_json: Dict[str, Any],
    trace: Dict[str, Any],
    spans: List[Dict[str, Any]],
    parent_by_child: Dict[str, Optional[str]],
    forced_tier: Optional[tier_module.CompressionTier] = None,
) -> tier_module.CompressionResult:
    """Render `full_json` at the appropriate tier and return the result.

    `forced_tier` lets the caller pin the tier (used by `read(tier=...)`);
    when None, the tier is picked from the token estimate. `parent_by_child`
    is only consulted at SKELETON tier to build the nested span tree.
    """
    tier = _pick_tier(full_json, forced_tier)
    if tier is tier_module.CompressionTier.FULL:
        payload: Dict[str, Any] = full_json
    elif tier is tier_module.CompressionTier.MEDIUM:
        payload = path_aware_truncator.truncate_strings(
            full_json, STRING_TRUNCATION_LENGTH
        )
    else:
        # SKELETON / SUMMARY collapse to the skeleton view; the reported
        # tier flattens SUMMARY → SKELETON since this compressor doesn't
        # ship a separate SUMMARY rendering.
        payload = _build_skeleton(trace, spans, parent_by_child)

    reported_tier = (
        tier_module.CompressionTier.SKELETON
        if tier is tier_module.CompressionTier.SUMMARY
        else tier
    )
    return tier_module.CompressionResult(payload=payload, tier=reported_tier)


def _pick_tier(
    full_json: Dict[str, Any], forced: Optional[tier_module.CompressionTier]
) -> tier_module.CompressionTier:
    if forced is not None:
        return forced
    estimate = tokens.estimate_tokens(full_json)
    if estimate < FULL_TOKEN_LIMIT:
        return tier_module.CompressionTier.FULL
    if estimate < MEDIUM_TOKEN_LIMIT:
        return tier_module.CompressionTier.MEDIUM
    return tier_module.CompressionTier.SKELETON


def _build_skeleton(
    trace: Dict[str, Any],
    spans: List[Dict[str, Any]],
    parent_by_child: Dict[str, Optional[str]],
) -> Dict[str, Any]:
    error_count = sum(1 for span in spans if span.get("error_info") is not None)
    return {
        "name": trace.get("name"),
        "project_name": trace.get("project_name"),
        "span_count": len(spans),
        "error_count": error_count,
        "total_duration_ms": _duration_ms(
            trace.get("start_time"), trace.get("end_time")
        ),
        "span_tree": _build_span_tree(spans, parent_by_child),
    }


def _build_span_tree(
    spans: List[Dict[str, Any]], parent_by_child: Dict[str, Optional[str]]
) -> List[Dict[str, Any]]:
    """Build a nested tree of `{id, name, type, spans: [...]}` nodes.

    Orphan spans (parent id present but parent span not in the list) are
    promoted to roots so they remain visible — same fallback the backend
    `SpanHierarchy.toTree` applies.
    """
    nodes: Dict[str, Dict[str, Any]] = {
        span["id"]: {
            "id": span["id"],
            "name": span.get("name"),
            "type": span.get("type"),
            "spans": [],
        }
        for span in spans
    }
    roots: List[Dict[str, Any]] = []
    for span in spans:
        span_id = span["id"]
        parent_id = parent_by_child.get(span_id)
        if parent_id is None or parent_id not in nodes:
            roots.append(nodes[span_id])
        else:
            nodes[parent_id]["spans"].append(nodes[span_id])
    return roots


def _duration_ms(start: Optional[str], end: Optional[str]) -> Optional[float]:
    if start is None or end is None:
        return None
    try:
        start_dt = datetime.datetime.fromisoformat(start)
        end_dt = datetime.datetime.fromisoformat(end)
    except (TypeError, ValueError):
        # Best-effort: skeleton-tier duration is informational, not load-bearing.
        LOGGER.info(
            "Failed to parse trace timestamps for duration_ms "
            "(start=%r, end=%r); returning None.",
            start,
            end,
            exc_info=True,
        )
        return None
    return (end_dt - start_dt).total_seconds() * 1000.0

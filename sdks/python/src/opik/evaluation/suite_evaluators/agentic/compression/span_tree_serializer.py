"""Span tree skeleton renderer for the agentic LLM judge.

Produces a compact JSON view of the trace's span tree — names, ids, types,
parent links, durations, and error flags — plus heavily truncated I/O so
the judge can spot relevant spans without reading each one. Mirrors the
backend's `SpanTreeSerializer` overview shape (Java); diverges only where
Python's dataclass shape forces it.
"""

import datetime
import json
import sys
from typing import Any, Dict, List, NamedTuple, Optional, Tuple

from opik.message_processing.emulation import models

from . import tokens


# Sentinel limit meaning "do not truncate." `_truncate_text` compares
# `len(text) <= limit`, so `sys.maxsize` falls through unchanged without
# needing a special-case branch. Lets the ladder express "use the full
# content if the budget allows" as a first-class tier.
NO_OVERVIEW_TRUNCATION: int = sys.maxsize

# Floor entry of the ladder: the smallest per-field truncation limit
# the sizer will fall back to when nothing larger fits the model's
# context budget. Matches the backend's
# `SpanTreeSerializer.OVERVIEW_TRUNCATION_LENGTH` (500). Earlier value
# was 200, which was small enough that even modest real-world payloads
# tripped truncation and forced the judge to drill in for every span —
# a per-token tax with no benefit, and a known engagement hazard. (A
# `gpt-4o-mini`-class judge interprets truncated content as evidence of
# absence rather than "go fetch the full value".) 500 gives the
# overview enough headroom for the common case while still bounding
# worst-case overview size.
OVERVIEW_IO_FLOOR_CHAR_LIMIT = 500


# Candidate per-field truncation limits, large → small. Callers pick
# the largest entry whose rendered overview fits the judge model's
# context budget (see `pick_overview_io_char_limit`). The top tier is
# `NO_OVERVIEW_TRUNCATION` — when the budget allows, the model sees
# full content for every field, no per-field cap. The budget check
# itself is the only sane upper bound; capping below the budget just
# forces a `read` round-trip for traces with one big field and many
# small ones, which is the failure mode this ladder was meant to
# avoid. The floor (500) preserves the historical default.
OVERVIEW_IO_LIMIT_LADDER: Tuple[int, ...] = (
    NO_OVERVIEW_TRUNCATION,
    64_000,
    32_000,
    16_000,
    8_000,
    4_000,
    2_000,
    1_000,
    OVERVIEW_IO_FLOOR_CHAR_LIMIT,
)

# Truncation suffix carries an *actionable* hint pointing at the
# specific `read(...)` call that recovers the un-truncated value.
# Mirrors the `[TRUNCATED N chars — use scan('<path>') to see full]`
# pattern emitted by `read`-MEDIUM (see `string_truncator.py`) — same
# rendering convention, different tool because the overview's
# entity-anchor is `(type, id)` rather than a jq path. Without this
# hint, models that see only `[TRUNCATED N chars]` tend to treat the
# truncated content as if it were absent. See the E2E transcript on
# OPIK-6243 for the failure mode.
TRUNCATED_SUFFIX_TEMPLATE = (
    "[TRUNCATED {n} chars — use read(type='{entity_type}', id='{entity_id}') "
    "to see full]"
)


class _TruncatedValue(NamedTuple):
    """A field value paired with whether `_truncate_text` had to trim it."""

    value: Any
    truncated: bool


class OverviewResult(NamedTuple):
    """Output of `serialize_overview`: the rendered overview plus an
    authoritative flag for whether any field was truncated.
    """

    overview: Dict[str, Any]
    has_truncations: bool


class SizedOverview(NamedTuple):
    """Output of `pick_overview_io_char_limit`: chosen ladder entry, the
    overview rendered at that limit, and whether truncation happened.
    """

    limit: int
    overview: Dict[str, Any]
    has_truncations: bool


def _truncate_text(
    value: Any,
    entity_type: str,
    entity_id: str,
    limit: int = OVERVIEW_IO_FLOOR_CHAR_LIMIT,
) -> _TruncatedValue:
    """Truncate strings or string-rendered dict/list to `limit` chars.

    Returned as-is when None / non-text / under limit. Larger values are
    rendered to JSON, head-trimmed, and suffixed with an actionable
    `read(...)` hint anchored at the specific entity that owns this
    field. The hint syntax matches the `read` tool's argument shape, so
    the judge can paste it directly.
    """
    if value is None:
        return _TruncatedValue(None, False)
    if isinstance(value, str):
        text = value
    else:
        try:
            text = json.dumps(value, default=str)
        except (TypeError, ValueError):
            text = str(value)
    if len(text) <= limit:
        return _TruncatedValue(text, False)
    dropped = len(text) - limit
    suffix = TRUNCATED_SUFFIX_TEMPLATE.format(
        n=f"{dropped:,}", entity_type=entity_type, entity_id=entity_id
    )
    return _TruncatedValue(text[:limit] + suffix, True)


def _duration_ms(
    start: datetime.datetime, end: Optional[datetime.datetime]
) -> Optional[float]:
    if end is None:
        return None
    return (end - start).total_seconds() * 1000.0


def _serialize_span_node(
    span: models.SpanModel,
    parent_id: Optional[str],
    io_char_limit: int,
) -> Tuple[Dict[str, Any], bool]:
    has_error = span.error_info is not None
    input_field = _truncate_text(
        span.input, entity_type="span", entity_id=span.id, limit=io_char_limit
    )
    output_field = _truncate_text(
        span.output, entity_type="span", entity_id=span.id, limit=io_char_limit
    )
    node = {
        "id": span.id,
        "name": span.name,
        "type": span.type,
        "parent_span_id": parent_id,
        "start_time": span.start_time.isoformat(),
        "duration_ms": _duration_ms(span.start_time, span.end_time),
        "has_error": has_error,
        "input": input_field.value,
        "output": output_field.value,
        "model": span.model,
        "provider": span.provider,
    }
    return node, input_field.truncated or output_field.truncated


def serialize_overview(
    trace: models.TraceModel,
    spans: List[models.SpanModel],
    parent_by_child: Dict[str, Optional[str]],
    io_char_limit: int = OVERVIEW_IO_FLOOR_CHAR_LIMIT,
) -> OverviewResult:
    """Render a flat overview of the trace + its spans.

    Returns `OverviewResult(overview, has_truncations)`. The flag is an
    authoritative signal that some field was trimmed — derived from the
    truncators themselves rather than from scanning the rendered output
    — so it stays correct even when user content quotes the truncation
    suffix template verbatim.

    Output shape of `overview`:
        {
          "trace": {id, name, duration_ms, has_error, span_count, error_count,
                    input(truncated), output(truncated)},
          "spans": [ {id, name, type, parent_span_id, ...}, ... ]
        }

    Spans are flat (parent_span_id surfaces the hierarchy). Parent links
    come from `parent_by_child` — the caller is expected to source this
    from `EmulatorMessageProcessor.parent_span_ids_for_trace`, since the
    nested `SpanModel.spans` list is only populated as a side effect of
    `trace_trees` and is unreliable on a freshly-fetched flat list.

    `io_char_limit` controls per-field truncation. The default matches
    the backend baseline; callers with a known model budget should
    prefer `pick_overview_io_char_limit` to choose a larger limit when
    it fits.
    """
    span_results = [
        _serialize_span_node(span, parent_by_child.get(span.id), io_char_limit)
        for span in spans
    ]
    flat_nodes = [node for node, _ in span_results]
    any_span_truncated = any(truncated for _, truncated in span_results)
    flat_nodes.sort(key=lambda n: n["start_time"])
    error_count = sum(1 for n in flat_nodes if n["has_error"])

    trace_input = _truncate_text(
        trace.input,
        entity_type="trace",
        entity_id=trace.id,
        limit=io_char_limit,
    )
    trace_output = _truncate_text(
        trace.output,
        entity_type="trace",
        entity_id=trace.id,
        limit=io_char_limit,
    )

    overview = {
        "trace": {
            "id": trace.id,
            "name": trace.name,
            "duration_ms": _duration_ms(trace.start_time, trace.end_time),
            "has_error": trace.error_info is not None,
            "span_count": len(flat_nodes),
            "error_count": error_count,
            "input": trace_input.value,
            "output": trace_output.value,
        },
        "spans": flat_nodes,
    }
    has_truncations = (
        any_span_truncated or trace_input.truncated or trace_output.truncated
    )
    return OverviewResult(overview, has_truncations)


def pick_overview_io_char_limit(
    *,
    trace: models.TraceModel,
    spans: List[models.SpanModel],
    parent_by_child: Dict[str, Optional[str]],
    budget_tokens: int,
    ladder: Optional[Tuple[int, ...]] = None,
) -> SizedOverview:
    """Pick the largest ladder entry whose rendered overview fits the budget.

    Returns `SizedOverview(limit, overview, has_truncations)` — the
    overview is built during the sizing walk, so returning it spares
    the caller a second render at the same limit, and the truncation
    flag is propagated from the underlying `serialize_overview` call.

    Walks `ladder` largest → smallest, rendering once per candidate and
    measuring against the cheap `tokens.estimate_tokens` proxy. Falls
    back to the smallest entry (re-rendered) if nothing fits — the
    agentic loop's drill-in tools can still rescue the run from there.

    `ladder` defaults to the module-level `OVERVIEW_IO_LIMIT_LADDER`,
    read at call time rather than at function-definition time so tests
    can `monkeypatch.setattr(span_tree_serializer,
    "OVERVIEW_IO_LIMIT_LADDER", ...)` and have it take effect.

    Rendering is O(spans) per candidate; the default ladder has 9
    entries, so worst-case is one digit of millis on the data we cache
    in `TraceToolContext`. Cheap relative to a single LLM round-trip.
    """
    if ladder is None:
        ladder = OVERVIEW_IO_LIMIT_LADDER
    if not ladder:
        raise ValueError("ladder must not be empty")
    if budget_tokens <= 0:
        floor = ladder[-1]
        result = serialize_overview(trace, spans, parent_by_child, io_char_limit=floor)
        return SizedOverview(
            floor, overview=result.overview, has_truncations=result.has_truncations
        )

    last_result: Optional[OverviewResult] = None
    for limit in ladder:
        result = serialize_overview(trace, spans, parent_by_child, io_char_limit=limit)
        if tokens.estimate_tokens(result.overview) <= budget_tokens:
            return SizedOverview(
                limit, overview=result.overview, has_truncations=result.has_truncations
            )
        last_result = result
    # Nothing fit. `last_result` is the render at the floor (ladder's
    # smallest entry); reuse it to avoid a redundant render.
    assert last_result is not None  # ladder was non-empty per the guard above
    return SizedOverview(
        ladder[-1],
        overview=last_result.overview,
        has_truncations=last_result.has_truncations,
    )

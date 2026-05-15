"""Span tree skeleton renderer for the agentic LLM judge.

Produces a compact JSON view of the trace's span tree — names, ids, types,
parent links, durations, and error flags — plus heavily truncated I/O so
the judge can spot relevant spans without reading each one. Mirrors the
backend's `SpanTreeSerializer` overview shape (Java); diverges only where
Python's dataclass shape forces it.
"""

import datetime
import json
from typing import Any, Dict, List, Optional

from opik.message_processing.emulation import models

# Matches the backend's `SpanTreeSerializer.OVERVIEW_TRUNCATION_LENGTH`
# (500). Earlier value was 200, which was small enough that even modest
# real-world payloads tripped truncation and forced the judge to drill
# in for every span — a per-token tax with no benefit, and a known
# engagement hazard (a `gpt-4o-mini`-class judge interprets truncated
# content as evidence of absence rather than "go fetch the full value").
# 500 gives the overview enough headroom for the common case while
# still bounding worst-case overview size.
OVERVIEW_IO_CHAR_LIMIT = 500

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


def _truncate_text(
    value: Any,
    entity_type: str,
    entity_id: str,
    limit: int = OVERVIEW_IO_CHAR_LIMIT,
) -> Any:
    """Truncate strings or string-rendered dict/list to `limit` chars.

    Returned as-is when None / non-text / under limit. Larger values are
    rendered to JSON, head-trimmed, and suffixed with an actionable
    `read(...)` hint anchored at the specific entity that owns this
    field. The hint syntax matches the `read` tool's argument shape so
    the judge can paste it directly.
    """
    if value is None:
        return None
    if isinstance(value, str):
        text = value
    else:
        try:
            text = json.dumps(value, default=str)
        except (TypeError, ValueError):
            text = str(value)
    if len(text) <= limit:
        return text
    dropped = len(text) - limit
    return text[:limit] + TRUNCATED_SUFFIX_TEMPLATE.format(
        n=f"{dropped:,}", entity_type=entity_type, entity_id=entity_id
    )


def _duration_ms(
    start: datetime.datetime, end: Optional[datetime.datetime]
) -> Optional[float]:
    if end is None:
        return None
    return (end - start).total_seconds() * 1000.0


def _serialize_span_node(
    span: models.SpanModel,
    parent_id: Optional[str],
) -> Dict[str, Any]:
    has_error = span.error_info is not None
    return {
        "id": span.id,
        "name": span.name,
        "type": span.type,
        "parent_span_id": parent_id,
        "start_time": span.start_time.isoformat(),
        "duration_ms": _duration_ms(span.start_time, span.end_time),
        "has_error": has_error,
        "input": _truncate_text(span.input, entity_type="span", entity_id=span.id),
        "output": _truncate_text(span.output, entity_type="span", entity_id=span.id),
        "model": span.model,
        "provider": span.provider,
    }


def serialize_overview(
    trace: models.TraceModel,
    spans: List[models.SpanModel],
    parent_by_child: Dict[str, Optional[str]],
) -> Dict[str, Any]:
    """Render a flat overview of the trace + its spans.

    Output shape:
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
    """
    flat_nodes = [
        _serialize_span_node(span, parent_by_child.get(span.id)) for span in spans
    ]
    flat_nodes.sort(key=lambda n: n["start_time"])
    error_count = sum(1 for n in flat_nodes if n["has_error"])

    return {
        "trace": {
            "id": trace.id,
            "name": trace.name,
            "duration_ms": _duration_ms(trace.start_time, trace.end_time),
            "has_error": trace.error_info is not None,
            "span_count": len(flat_nodes),
            "error_count": error_count,
            "input": _truncate_text(
                trace.input, entity_type="trace", entity_id=trace.id
            ),
            "output": _truncate_text(
                trace.output, entity_type="trace", entity_id=trace.id
            ),
        },
        "spans": flat_nodes,
    }

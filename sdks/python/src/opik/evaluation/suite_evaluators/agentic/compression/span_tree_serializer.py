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

OVERVIEW_IO_CHAR_LIMIT = 200
TRUNCATED_SUFFIX_TEMPLATE = "[TRUNCATED {n} chars]"


def _truncate_text(value: Any, limit: int = OVERVIEW_IO_CHAR_LIMIT) -> Any:
    """Truncate strings or string-rendered dict/list to `limit` chars.

    Returned as-is when None / non-text / under limit. Larger values are
    rendered to JSON, head-trimmed, and suffixed with [TRUNCATED N chars].
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
    return text[:limit] + TRUNCATED_SUFFIX_TEMPLATE.format(n=f"{dropped:,}")


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
        "input": _truncate_text(span.input),
        "output": _truncate_text(span.output),
        "model": span.model,
        "provider": span.provider,
    }


def serialize_overview(
    trace: models.TraceModel,
    spans: List[models.SpanModel],
) -> Dict[str, Any]:
    """Render a flat overview of the trace + its spans.

    Output shape:
        {
          "trace": {id, name, duration_ms, has_error, span_count, error_count,
                    input(truncated), output(truncated)},
          "spans": [ {id, name, type, parent_span_id, ...}, ... ]
        }

    Spans are flat (parent_span_id surfaces the hierarchy). The flat
    representation is easier for the judge to reason about than nested
    trees and keeps token count predictable.
    """
    parent_by_child: Dict[str, Optional[str]] = {}

    def walk(parent: Optional[str], node: models.SpanModel) -> None:
        parent_by_child[node.id] = parent
        for child in node.spans:
            walk(node.id, child)

    for top in spans:
        walk(None, top)

    flat_nodes: List[Dict[str, Any]] = []

    def collect(node: models.SpanModel) -> None:
        flat_nodes.append(_serialize_span_node(node, parent_by_child.get(node.id)))
        for child in node.spans:
            collect(child)

    for top in spans:
        collect(top)

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
            "input": _truncate_text(trace.input),
            "output": _truncate_text(trace.output),
        },
        "spans": flat_nodes,
    }

import dataclasses
import datetime
import logging
from typing import Any, Dict, List, Optional

from opik.message_processing.emulation import (
    emulator_message_processor,
    models,
)

from . import entity_ref
from .compression import trace_compressor

LOGGER = logging.getLogger(__name__)


def _isoformat(value: Optional[datetime.datetime]) -> Optional[str]:
    if value is None:
        return None
    return value.isoformat()


def _serialize_span(
    span: models.SpanModel, parent_span_id: Optional[str] = None
) -> Dict[str, Any]:
    """Serialize a SpanModel to a JSON-shaped dict.

    Child spans are intentionally NOT inlined here; the span tree is rendered
    separately via SpanTreeSerializer / GetTraceSpansTool so that the cache
    holds one entry per span and `read(type=span, id=...)` returns just that
    span. Children references would otherwise produce duplicate data and
    inconsistent caching behavior.

    `parent_span_id` is sourced from the emulator's span-to-parent map at
    cache time so the SKELETON tier of `read(type=trace, ...)` can rebuild
    the nested span tree without needing the live emulator.
    """
    return {
        "id": span.id,
        "name": span.name,
        "type": span.type,
        "parent_span_id": parent_span_id,
        "project_name": span.project_name,
        "start_time": _isoformat(span.start_time),
        "end_time": _isoformat(span.end_time),
        "input": span.input,
        "output": span.output,
        "metadata": span.metadata,
        "tags": span.tags,
        "usage": span.usage,
        "model": span.model,
        "provider": span.provider,
        "error_info": span.error_info,
        "total_cost": span.total_cost,
    }


def _serialize_trace(
    trace: models.TraceModel,
) -> Dict[str, Any]:
    return {
        "id": trace.id,
        "name": trace.name,
        "project_name": trace.project_name,
        "thread_id": trace.thread_id,
        "start_time": _isoformat(trace.start_time),
        "end_time": _isoformat(trace.end_time),
        "input": trace.input,
        "output": trace.output,
        "metadata": trace.metadata,
        "tags": trace.tags,
        "error_info": trace.error_info,
    }


@dataclasses.dataclass
class TraceToolContext:
    """Per-evaluation cache plumbed into agentic-judge tools.

    Pre-seeds the active trace and its spans on construction so `scan` /
    `search` (later phases) see them without a preceding `read`. Backed by
    the LocalEmulatorMessageProcessor; no network fetches.
    """

    trace: models.TraceModel
    spans: List[models.SpanModel]
    parent_by_child: Dict[str, Optional[str]]
    emulator: emulator_message_processor.EmulatorMessageProcessor
    _fetched: Dict[entity_ref.EntityRef, Dict[str, Any]] = dataclasses.field(
        default_factory=dict
    )

    def __post_init__(self) -> None:
        # Trace cache holds the COMPOSITE `{"trace": ..., "spans": [...]}`
        # so `scan(type=trace, expression='.spans[0].input')` works without
        # a preceding `read`. Matches backend cache semantics
        # (see TraceCompressor.buildFullJson on the Java side).
        span_dicts = [
            _serialize_span(span, self.parent_by_child.get(span.id))
            for span in self.spans
        ]
        self._fetched[
            entity_ref.EntityRef(entity_ref.EntityType.TRACE, self.trace.id)
        ] = trace_compressor.build_full_json(_serialize_trace(self.trace), span_dicts)
        for span, span_dict in zip(self.spans, span_dicts):
            self._fetched[entity_ref.EntityRef(entity_ref.EntityType.SPAN, span.id)] = (
                span_dict
            )

    def get_cached(self, ref: entity_ref.EntityRef) -> Optional[Dict[str, Any]]:
        return self._fetched.get(ref)

    def cache(self, ref: entity_ref.EntityRef, payload: Dict[str, Any]) -> None:
        self._fetched[ref] = payload

    def lookup_from_emulator(
        self, ref: entity_ref.EntityRef
    ) -> Optional[Dict[str, Any]]:
        """Best-effort resolution against the local emulator for non-pre-seeded entities.

        Traces resolve to the composite `{"trace": ..., "spans": [...]}`
        shape used by the pre-seeded cache. Spans resolve to a flat span
        dict. Only TRACE and SPAN are in scope for v1.
        """
        if ref.type == entity_ref.EntityType.TRACE:
            trace = self.emulator.get_trace(ref.id)
            if trace is None:
                return None
            spans = self.emulator.spans_for_trace(ref.id)
            parent_map = self.emulator.parent_span_ids_for_trace(ref.id)
            return trace_compressor.build_full_json(
                _serialize_trace(trace),
                [_serialize_span(span, parent_map.get(span.id)) for span in spans],
            )
        if ref.type == entity_ref.EntityType.SPAN:
            span = self.emulator.get_span(ref.id)
            if span is None:
                return None
            trace_id = self.emulator.trace_id_for_span(span.id)
            parent_id = (
                self.emulator.parent_span_ids_for_trace(trace_id).get(span.id)
                if trace_id is not None
                else None
            )
            return _serialize_span(span, parent_id)
        return None


def build_trace_tool_context(
    trace_id: str,
    emulator: emulator_message_processor.EmulatorMessageProcessor,
) -> Optional[TraceToolContext]:
    """Construct a context for `trace_id` if the emulator has the trace.

    Returns None when the trace isn't in the emulator cache — caller falls
    back to the one-shot judge path.
    """
    trace = emulator.get_trace(trace_id)
    if trace is None:
        LOGGER.debug(
            "TraceToolContext: trace %s not in emulator cache; agentic path skipped",
            trace_id,
        )
        return None

    spans = emulator.spans_for_trace(trace_id)
    parent_by_child = emulator.parent_span_ids_for_trace(trace_id)
    return TraceToolContext(
        trace=trace,
        spans=spans,
        parent_by_child=parent_by_child,
        emulator=emulator,
    )

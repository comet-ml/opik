import dataclasses
import datetime
import logging
from typing import Any, Dict, List, Optional

from opik.message_processing.emulation import (
    emulator_message_processor,
    models,
)

from . import entity_ref

LOGGER = logging.getLogger(__name__)


def _isoformat(value: Optional[datetime.datetime]) -> Optional[str]:
    if value is None:
        return None
    return value.isoformat()


def _serialize_span(span: models.SpanModel) -> Dict[str, Any]:
    """Serialize a SpanModel to a JSON-shaped dict.

    Child spans are intentionally NOT inlined here; the span tree is rendered
    separately via SpanTreeSerializer / GetTraceSpansTool so that the cache
    holds one entry per span and `read(type=span, id=...)` returns just that
    span. Children references would otherwise produce duplicate data and
    inconsistent caching behavior.
    """
    return {
        "id": span.id,
        "name": span.name,
        "type": span.type,
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
    emulator: emulator_message_processor.EmulatorMessageProcessor
    _fetched: Dict[entity_ref.EntityRef, Dict[str, Any]] = dataclasses.field(
        default_factory=dict
    )

    def __post_init__(self) -> None:
        self._fetched[
            entity_ref.EntityRef(entity_ref.EntityType.TRACE, self.trace.id)
        ] = _serialize_trace(self.trace)
        for span in self.spans:
            self._fetched[entity_ref.EntityRef(entity_ref.EntityType.SPAN, span.id)] = (
                _serialize_span(span)
            )

    def get_cached(self, ref: entity_ref.EntityRef) -> Optional[Dict[str, Any]]:
        return self._fetched.get(ref)

    def cache(self, ref: entity_ref.EntityRef, payload: Dict[str, Any]) -> None:
        self._fetched[ref] = payload

    def lookup_from_emulator(
        self, ref: entity_ref.EntityRef
    ) -> Optional[Dict[str, Any]]:
        """Best-effort resolution against the local emulator for non-pre-seeded entities.

        Returns serialized dicts. Other entity types (DATASET, DATASET_ITEM,
        PROJECT, THREAD) are not yet supported and return None — they will
        land alongside `read` in Phase 2.
        """
        if ref.type == entity_ref.EntityType.TRACE:
            trace = self.emulator.get_trace(ref.id)
            return _serialize_trace(trace) if trace is not None else None
        if ref.type == entity_ref.EntityType.SPAN:
            span = self.emulator.get_span(ref.id)
            return _serialize_span(span) if span is not None else None
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
    return TraceToolContext(trace=trace, spans=spans, emulator=emulator)

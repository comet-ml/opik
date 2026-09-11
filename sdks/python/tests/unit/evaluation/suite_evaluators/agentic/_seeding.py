"""Shared test seeding helpers for the agentic-judge test suite.

Centralizes the "feed a trace + spans into the emulator via the public
message API and build a TraceToolContext from it" flow so individual
tests don't poke `_trace_observations` / `_span_observations` /
`_span_to_trace` / `_span_to_parent_span` directly. Those attributes
are private to `EmulatorMessageProcessor` and have already changed
shape once; tests reaching into them would break silently on the next
internal refactor.
"""

from typing import Dict, Iterable, Optional

from opik.evaluation.suite_evaluators.agentic.context import TraceToolContext
from opik.message_processing import messages
from opik.message_processing.emulation import (
    local_emulator_message_processor,
    models,
)


def make_emulator() -> local_emulator_message_processor.LocalEmulatorMessageProcessor:
    """Construct a fresh active emulator."""
    return local_emulator_message_processor.LocalEmulatorMessageProcessor(active=True)


def seed_trace(
    emulator: local_emulator_message_processor.LocalEmulatorMessageProcessor,
    trace: models.TraceModel,
) -> None:
    """Emit a `CreateTraceMessage` so `trace` is observable via emulator
    public methods (`get_trace`, `spans_for_trace`, ...).
    """
    emulator.process(
        messages.CreateTraceMessage(
            trace_id=trace.id,
            project_name=trace.project_name,
            name=trace.name,
            start_time=trace.start_time,
            end_time=trace.end_time,
            input=trace.input,
            output=trace.output,
            metadata=trace.metadata,
            tags=trace.tags,
            error_info=trace.error_info,
            thread_id=trace.thread_id,
            last_updated_at=trace.last_updated_at,
            source=trace.source,
        )
    )


def seed_span(
    emulator: local_emulator_message_processor.LocalEmulatorMessageProcessor,
    span: models.SpanModel,
    trace_id: str,
    parent_span_id: Optional[str] = None,
) -> None:
    """Emit a `CreateSpanMessage` so `span` is observable via emulator
    public methods.
    """
    emulator.process(
        messages.CreateSpanMessage(
            span_id=span.id,
            trace_id=trace_id,
            project_name=span.project_name,
            parent_span_id=parent_span_id,
            name=span.name,
            start_time=span.start_time,
            end_time=span.end_time,
            input=span.input,
            output=span.output,
            metadata=span.metadata,
            tags=span.tags,
            type=span.type,
            usage=span.usage,
            model=span.model,
            provider=span.provider,
            error_info=span.error_info,
            total_cost=span.total_cost,
            last_updated_at=span.last_updated_at,
            source=span.source,
        )
    )


def build_ctx(
    trace: models.TraceModel,
    spans: Iterable[models.SpanModel],
    parent_by_child: Optional[Dict[str, Optional[str]]] = None,
) -> TraceToolContext:
    """Build a `TraceToolContext` whose emulator has been seeded with
    `trace` + `spans` via the public message API.

    `parent_by_child` defaults to flat (all spans parentless) when omitted.
    """
    span_list = list(spans)
    parent_map: Dict[str, Optional[str]] = (
        dict(parent_by_child) if parent_by_child is not None else {}
    )
    for span in span_list:
        parent_map.setdefault(span.id, None)

    emulator = make_emulator()
    seed_trace(emulator, trace)
    for span in span_list:
        seed_span(emulator, span, trace.id, parent_map.get(span.id))

    return TraceToolContext(
        trace=trace,
        spans=span_list,
        parent_by_child=parent_map,
        emulator=emulator,
    )

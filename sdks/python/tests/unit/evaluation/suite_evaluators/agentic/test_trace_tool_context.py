import datetime

from opik.message_processing.emulation import (
    local_emulator_message_processor,
    models,
)

from opik.evaluation.suite_evaluators.agentic.context import (
    TraceToolContext,
    build_trace_tool_context,
)
from opik.evaluation.suite_evaluators.agentic.entity_ref import EntityRef, EntityType


def _now():
    return datetime.datetime(2026, 5, 13, 12, 0, 0)


def _trace(trace_id="t-1"):
    return models.TraceModel(
        id=trace_id,
        start_time=_now(),
        name="t",
        project_name="default",
        source="sdk",
        input={"q": "hi"},
        output={"a": "hello"},
        end_time=_now() + datetime.timedelta(seconds=1),
    )


def _span(span_id, start_offset_s=0):
    return models.SpanModel(
        id=span_id,
        start_time=_now() + datetime.timedelta(seconds=start_offset_s),
        source="sdk",
        name=span_id,
        type="general",
    )


def _emulator_with(trace, spans):
    emulator = local_emulator_message_processor.LocalEmulatorMessageProcessor(
        active=True
    )
    emulator._trace_observations[trace.id] = trace
    for span in spans:
        emulator._span_observations[span.id] = span
        emulator._span_to_trace[span.id] = trace.id
        emulator._span_to_parent_span[span.id] = None
    return emulator


class TestTraceToolContextPreseed:
    def test_active_trace_cached(self):
        trace = _trace()
        ctx = TraceToolContext(
            trace=trace,
            spans=[],
            parent_by_child={},
            emulator=_emulator_with(trace, []),
        )
        cached = ctx.get_cached(EntityRef(EntityType.TRACE, trace.id))
        assert cached is not None
        assert cached["id"] == trace.id
        assert cached["input"] == {"q": "hi"}

    def test_active_spans_cached(self):
        trace = _trace()
        spans = [_span("s-1"), _span("s-2", start_offset_s=1)]
        ctx = TraceToolContext(
            trace=trace,
            spans=spans,
            parent_by_child={"s-1": None, "s-2": None},
            emulator=_emulator_with(trace, spans),
        )
        assert ctx.get_cached(EntityRef(EntityType.SPAN, "s-1")) is not None
        assert ctx.get_cached(EntityRef(EntityType.SPAN, "s-2")) is not None

    def test_unknown_entity_returns_none(self):
        trace = _trace()
        ctx = TraceToolContext(
            trace=trace,
            spans=[],
            parent_by_child={},
            emulator=_emulator_with(trace, []),
        )
        assert ctx.get_cached(EntityRef(EntityType.SPAN, "missing")) is None


class TestBuildTraceToolContext:
    def test_returns_none_when_trace_missing(self):
        emulator = _emulator_with(_trace(), [])
        assert build_trace_tool_context("nope", emulator) is None

    def test_returns_context_with_pre_seeded_spans(self):
        trace = _trace()
        spans = [_span("s-1"), _span("s-2", start_offset_s=1)]
        emulator = _emulator_with(trace, spans)
        ctx = build_trace_tool_context(trace.id, emulator)
        assert ctx is not None
        assert {s.id for s in ctx.spans} == {"s-1", "s-2"}
        # Spans are sorted by start_time.
        assert [s.id for s in ctx.spans] == ["s-1", "s-2"]
        # Parent links are pulled from the emulator alongside the spans.
        assert ctx.parent_by_child == {"s-1": None, "s-2": None}

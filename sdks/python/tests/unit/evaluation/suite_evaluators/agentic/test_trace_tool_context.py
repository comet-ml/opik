import datetime

from opik.message_processing.emulation import models

from opik.evaluation.suite_evaluators.agentic.context import (
    INTERNAL_SPAN_TAG,
    TraceToolContext,
    build_trace_tool_context,
)
from opik.evaluation.suite_evaluators.agentic.entity_ref import EntityRef, EntityType

from . import _seeding


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
    """Seed a fresh emulator with `trace` and `spans` via the public
    message API so tests don't reach into private storage."""
    emulator = _seeding.make_emulator()
    _seeding.seed_trace(emulator, trace)
    for span in spans:
        _seeding.seed_span(emulator, span, trace_id=trace.id)
    return emulator


class TestTraceToolContextPreseed:
    def test_get_cached__active_trace__returns_composite(self):
        trace = _trace()
        ctx = TraceToolContext(
            trace=trace,
            spans=[],
            parent_by_child={},
            emulator=_emulator_with(trace, []),
        )
        cached = ctx.get_cached(EntityRef(EntityType.TRACE, trace.id))
        # Trace cache holds the composite {trace, spans} shape — this is
        # what `scan` queries against, so paths like `.trace.input` or
        # `.spans[0].name` resolve from a single cached entry.
        assert cached is not None
        assert cached["trace"]["id"] == trace.id
        assert cached["trace"]["input"] == {"q": "hi"}
        assert cached["spans"] == []

    def test_get_cached__active_spans__returns_cached_entries(self):
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

    def test_get_cached__unknown_entity__returns_none(self):
        trace = _trace()
        ctx = TraceToolContext(
            trace=trace,
            spans=[],
            parent_by_child={},
            emulator=_emulator_with(trace, []),
        )
        assert ctx.get_cached(EntityRef(EntityType.SPAN, "missing")) is None


class TestBuildTraceToolContext:
    def test_build__missing_trace__returns_none(self):
        emulator = _emulator_with(_trace(), [])
        assert build_trace_tool_context("nope", emulator) is None

    def test_build__pre_seeded_spans__returns_context_sorted_by_start_time(self):
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

    def test_build__filters_internal_tagged_subtree(self):
        """Spans tagged `INTERNAL_SPAN_TAG` are opik's eval-engine
        plumbing — they echo assertion config back into the trace, which
        leaks assertion text and confuses the judge. The agentic context
        must drop the tagged span and its descendants (child scorer
        spans, model wrappers, etc.) before the judge sees the trace.
        """
        trace = _trace()
        # User-agent spans we want to keep.
        agent_root = models.SpanModel(
            id="agent",
            start_time=_now(),
            source="sdk",
            name="task",
            type="general",
        )
        agent_child = models.SpanModel(
            id="agent-child",
            start_time=_now() + datetime.timedelta(milliseconds=1),
            source="sdk",
            name="process_step",
            type="general",
        )
        # Eval-engine span — name is incidental; what matters is the tag.
        metrics_root = models.SpanModel(
            id="metrics",
            start_time=_now() + datetime.timedelta(milliseconds=2),
            source="sdk",
            name="metrics_calculation",
            type="general",
            tags=[INTERNAL_SPAN_TAG],
        )
        # Untagged descendant of the eval-engine span. Subtree-sweep
        # should drop it too — child scorers, model wrappers etc. are
        # internal regardless of their own tags.
        metrics_child = models.SpanModel(
            id="metrics-child",
            start_time=_now() + datetime.timedelta(milliseconds=3),
            source="sdk",
            name="some_scorer",
            type="general",
        )
        emulator = _seeding.make_emulator()
        _seeding.seed_trace(emulator, trace)
        _seeding.seed_span(emulator, agent_root, trace_id=trace.id)
        _seeding.seed_span(
            emulator, agent_child, trace_id=trace.id, parent_span_id="agent"
        )
        _seeding.seed_span(emulator, metrics_root, trace_id=trace.id)
        _seeding.seed_span(
            emulator,
            metrics_child,
            trace_id=trace.id,
            parent_span_id="metrics",
        )

        ctx = build_trace_tool_context(trace.id, emulator)
        assert ctx is not None
        kept_ids = {s.id for s in ctx.spans}
        assert kept_ids == {"agent", "agent-child"}
        # Parent map also pruned of internal-span entries.
        assert "metrics" not in ctx.parent_by_child
        assert "metrics-child" not in ctx.parent_by_child

    def test_build__user_span_named_metrics_calculation_is_kept(self):
        """Regression: previously the filter matched by `span.name`, so
        a legitimate user span named `metrics_calculation` (e.g. via
        `@opik.track(name="metrics_calculation")`) would be silently
        dropped from the judge's view. The marker-based filter must
        retain it — only spans the eval engine itself tags as internal
        are removed.
        """
        trace = _trace()
        user_span = models.SpanModel(
            id="user-mc",
            start_time=_now(),
            source="sdk",
            name="metrics_calculation",  # collides with the old name filter
            type="general",
            tags=None,
        )
        emulator = _seeding.make_emulator()
        _seeding.seed_trace(emulator, trace)
        _seeding.seed_span(emulator, user_span, trace_id=trace.id)

        ctx = build_trace_tool_context(trace.id, emulator)
        assert ctx is not None
        assert {s.id for s in ctx.spans} == {"user-mc"}

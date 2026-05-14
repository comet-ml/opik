"""Unit tests for the `read` tool.

Covers argument parsing, cache-vs-emulator resolution, dispatch to the
correct compressor, tier reporting, and structured error responses.
"""

import datetime
import json

from opik.evaluation.suite_evaluators.agentic.context import TraceToolContext
from opik.evaluation.suite_evaluators.agentic.tools.read import ReadTool
from opik.message_processing.emulation import (
    local_emulator_message_processor,
    models,
)


def _now():
    return datetime.datetime(2026, 5, 13, 12, 0, 0)


def _trace(trace_id="t-1", **overrides):
    base = dict(
        id=trace_id,
        start_time=_now(),
        end_time=_now() + datetime.timedelta(seconds=1),
        name="trace",
        project_name="default",
        source="sdk",
        input={"q": "hi"},
        output={"a": "there"},
    )
    base.update(overrides)
    return models.TraceModel(**base)


def _span(span_id, start_offset_s=0, **overrides):
    base = dict(
        id=span_id,
        start_time=_now() + datetime.timedelta(seconds=start_offset_s),
        source="sdk",
        name=span_id,
        type="general",
    )
    base.update(overrides)
    return models.SpanModel(**base)


def _ctx(trace, spans, parent_by_child=None):
    parent_by_child = parent_by_child or {s.id: None for s in spans}
    emulator = local_emulator_message_processor.LocalEmulatorMessageProcessor(
        active=True
    )
    emulator._trace_observations[trace.id] = trace
    for span in spans:
        emulator._span_observations[span.id] = span
        emulator._span_to_trace[span.id] = trace.id
        emulator._span_to_parent_span[span.id] = parent_by_child.get(span.id)
    return TraceToolContext(
        trace=trace,
        spans=spans,
        parent_by_child=parent_by_child,
        emulator=emulator,
    )


class TestArgumentParsing:
    def test_missing_type_returns_error(self):
        tool = ReadTool()
        ctx = _ctx(_trace(), [])

        response = json.loads(tool.execute('{"id": "x"}', ctx))

        assert "error" in response
        assert "type" in response["error"].lower()

    def test_missing_id_returns_error(self):
        tool = ReadTool()
        ctx = _ctx(_trace(), [])

        response = json.loads(tool.execute('{"type": "trace"}', ctx))

        assert "error" in response
        assert "id" in response["error"].lower()

    def test_unsupported_type_returns_error(self):
        tool = ReadTool()
        ctx = _ctx(_trace(), [])

        response = json.loads(
            tool.execute('{"type": "not_known_type", "id": "d-1"}', ctx)
        )

        assert "error" in response
        assert "not_known_type" in response["error"].lower()

    def test_invalid_tier_returns_error(self):
        tool = ReadTool()
        ctx = _ctx(_trace(), [])

        response = json.loads(
            tool.execute('{"type": "trace", "id": "t-1", "tier": "TINY"}', ctx)
        )

        assert "error" in response
        assert "TINY" in response["error"]

    def test_malformed_arguments_json_returns_error(self):
        tool = ReadTool()
        ctx = _ctx(_trace(), [])

        response = json.loads(tool.execute("not json", ctx))

        assert "error" in response


class TestReadTrace:
    def test_active_trace_returned_at_full_tier_by_default(self):
        trace = _trace()
        spans = [_span("s-1")]
        ctx = _ctx(trace, spans)
        tool = ReadTool()

        response = json.loads(
            tool.execute(json.dumps({"type": "trace", "id": trace.id}), ctx)
        )

        assert response["type"] == "trace"
        assert response["id"] == trace.id
        assert response["tier"] == "FULL"
        assert response["data"]["trace"]["id"] == trace.id
        assert [s["id"] for s in response["data"]["spans"]] == ["s-1"]

    def test_forced_skeleton_returns_minimal_tree(self):
        trace = _trace()
        spans = [_span("root"), _span("child", start_offset_s=1)]
        parents = {"root": None, "child": "root"}
        ctx = _ctx(trace, spans, parent_by_child=parents)
        tool = ReadTool()

        response = json.loads(
            tool.execute(
                json.dumps({"type": "trace", "id": trace.id, "tier": "SKELETON"}),
                ctx,
            )
        )

        assert response["tier"] == "SKELETON"
        root_nodes = response["data"]["span_tree"]
        assert len(root_nodes) == 1
        assert root_nodes[0]["id"] == "root"
        assert root_nodes[0]["spans"][0]["id"] == "child"

    def test_unknown_trace_id_returns_not_found(self):
        ctx = _ctx(_trace(), [])
        tool = ReadTool()

        response = json.loads(tool.execute('{"type": "trace", "id": "missing"}', ctx))

        assert "error" in response
        assert "not found" in response["error"].lower()


class TestReadSpan:
    def test_active_span_returned_at_full_tier(self):
        trace = _trace()
        spans = [_span("s-1", input={"k": "v"})]
        ctx = _ctx(trace, spans)
        tool = ReadTool()

        response = json.loads(tool.execute('{"type": "span", "id": "s-1"}', ctx))

        assert response["tier"] == "FULL"
        assert response["data"]["id"] == "s-1"
        assert response["data"]["input"] == {"k": "v"}

    def test_span_resolved_via_emulator_when_not_preseeded(self):
        # The emulator may have spans from other evaluation items in
        # scope; the read tool should resolve them via the emulator
        # fallback even though they weren't preseeded into the cache.
        trace = _trace()
        active_span = _span("s-active")
        other_span = _span("s-other")
        ctx = _ctx(trace, [active_span])
        # Add another span to the emulator that wasn't in the active set.
        ctx.emulator._span_observations[other_span.id] = other_span
        ctx.emulator._span_to_trace[other_span.id] = trace.id
        ctx.emulator._span_to_parent_span[other_span.id] = None

        tool = ReadTool()
        response = json.loads(tool.execute('{"type": "span", "id": "s-other"}', ctx))

        assert response["data"]["id"] == "s-other"

    def test_unknown_span_returns_not_found(self):
        ctx = _ctx(_trace(), [])
        tool = ReadTool()

        response = json.loads(tool.execute('{"type": "span", "id": "missing"}', ctx))

        assert "error" in response
        assert "not found" in response["error"].lower()


class TestSpec:
    def test_spec_exposes_only_in_scope_entity_types(self):
        # Schema should match the trimmed EntityType enum — TRACE / SPAN
        # only. Future scope expansion must update both sides in lockstep.
        enum_values = ReadTool.spec["function"]["parameters"]["properties"]["type"][
            "enum"
        ]
        assert sorted(enum_values) == ["span", "trace"]

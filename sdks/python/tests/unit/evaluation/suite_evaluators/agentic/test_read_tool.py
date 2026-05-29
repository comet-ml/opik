"""Unit tests for the `read` tool.

Covers argument parsing, cache-vs-emulator resolution, dispatch to the
correct compressor, tier reporting, and structured error responses.
"""

import datetime
import json

from opik.evaluation.suite_evaluators.agentic.tools.read import ReadTool
from opik.message_processing.emulation import models

from . import _seeding


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
    return _seeding.build_ctx(trace, spans, parent_by_child)


class TestArgumentParsing:
    def test_read__missing_type__returns_error(self):
        tool = ReadTool()
        ctx = _ctx(_trace(), [])

        response = json.loads(tool.execute('{"id": "x"}', ctx))

        assert "error" in response
        assert "type" in response["error"].lower()

    def test_read__missing_id__returns_error(self):
        tool = ReadTool()
        ctx = _ctx(_trace(), [])

        response = json.loads(tool.execute('{"type": "trace"}', ctx))

        assert "error" in response
        assert "id" in response["error"].lower()

    def test_read__unsupported_type__returns_error(self):
        tool = ReadTool()
        ctx = _ctx(_trace(), [])

        response = json.loads(
            tool.execute('{"type": "not_known_type", "id": "d-1"}', ctx)
        )

        assert "error" in response
        assert "not_known_type" in response["error"].lower()

    def test_read__invalid_tier__returns_error(self):
        tool = ReadTool()
        ctx = _ctx(_trace(), [])

        response = json.loads(
            tool.execute('{"type": "trace", "id": "t-1", "tier": "TINY"}', ctx)
        )

        assert "error" in response
        assert "TINY" in response["error"]

    def test_read__malformed_arguments_json__returns_error(self):
        tool = ReadTool()
        ctx = _ctx(_trace(), [])

        response = json.loads(tool.execute("not json", ctx))

        assert "error" in response


class TestReadTrace:
    def test_read__active_trace__returns_full_tier_by_default(self):
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

    def test_read__forced_skeleton__returns_minimal_tree(self):
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

    def test_read__unknown_trace_id__returns_not_found(self):
        ctx = _ctx(_trace(), [])
        tool = ReadTool()

        response = json.loads(tool.execute('{"type": "trace", "id": "missing"}', ctx))

        assert "error" in response
        assert "not found" in response["error"].lower()


class TestReadSpan:
    def test_read__active_span__returns_full_tier(self):
        trace = _trace()
        spans = [_span("s-1", input={"k": "v"})]
        ctx = _ctx(trace, spans)
        tool = ReadTool()

        response = json.loads(tool.execute('{"type": "span", "id": "s-1"}', ctx))

        assert response["tier"] == "FULL"
        assert response["data"]["id"] == "s-1"
        assert response["data"]["input"] == {"k": "v"}

    def test_read__span_not_preseeded__resolved_via_emulator(self):
        # The emulator may have spans from other evaluation items in
        # scope; the read tool should resolve them via the emulator
        # fallback even though they weren't preseeded into the cache.
        trace = _trace()
        active_span = _span("s-active")
        other_span = _span("s-other")
        ctx = _ctx(trace, [active_span])
        # Add another span to the emulator that wasn't in the active set.
        _seeding.seed_span(ctx.emulator, other_span, trace_id=trace.id)

        tool = ReadTool()
        response = json.loads(tool.execute('{"type": "span", "id": "s-other"}', ctx))

        assert response["data"]["id"] == "s-other"

    def test_read__unknown_span__returns_not_found(self):
        ctx = _ctx(_trace(), [])
        tool = ReadTool()

        response = json.loads(tool.execute('{"type": "span", "id": "missing"}', ctx))

        assert "error" in response
        assert "not found" in response["error"].lower()


class TestSpec:
    def test_spec__entity_type_enum__exposes_only_in_scope_types(self):
        # Schema should match the trimmed EntityType enum — TRACE / SPAN
        # only. Future scope expansion must update both sides in lockstep.
        enum_values = ReadTool.spec["function"]["parameters"]["properties"]["type"][
            "enum"
        ]
        assert sorted(enum_values) == ["span", "trace"]

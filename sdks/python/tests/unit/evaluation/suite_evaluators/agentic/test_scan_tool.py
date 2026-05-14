"""Unit tests for the `scan` tool.

Covers argument parsing, cache-vs-emulator resolution, output envelope
formatting, error propagation from the evaluator, and the 16 KB output
cap.
"""

import datetime
import json

from opik.message_processing.emulation import models

from opik.evaluation.suite_evaluators.agentic.tools import scan as scan_module

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
    def test_missing_expression_returns_error(self):
        ctx = _ctx(_trace(), [])
        tool = scan_module.ScanTool()

        result = tool.execute(json.dumps({"type": "trace", "id": "t-1"}), ctx)

        assert "ERROR" in result
        assert "expression" in result.lower()

    def test_unsupported_entity_type_returns_error(self):
        ctx = _ctx(_trace(), [])
        tool = scan_module.ScanTool()

        result = tool.execute(
            json.dumps({"type": "dataset", "id": "d-1", "expression": "."}),
            ctx,
        )

        assert "ERROR" in result
        assert "dataset" in result.lower()

    def test_malformed_arguments_json_returns_error(self):
        ctx = _ctx(_trace(), [])
        tool = scan_module.ScanTool()

        result = tool.execute("not json", ctx)

        assert "ERROR" in result


class TestScanAgainstActiveTrace:
    def test_root_returns_composite(self):
        trace = _trace()
        spans = [_span("root")]
        ctx = _ctx(trace, spans)
        tool = scan_module.ScanTool()

        result = tool.execute(
            json.dumps({"type": "trace", "id": trace.id, "expression": "."}),
            ctx,
        )

        # Envelope header.
        assert result.startswith("[scan: trace:t-1")
        # Body contains the trace composite — JSON-rendered.
        body = result.split("\n", 1)[1]
        parsed = json.loads(body)
        assert parsed["trace"]["id"] == trace.id
        assert [s["id"] for s in parsed["spans"]] == ["root"]

    def test_field_access_returns_value(self):
        ctx = _ctx(_trace(input={"prompt": "hello"}), [])
        tool = scan_module.ScanTool()

        result = tool.execute(
            json.dumps(
                {
                    "type": "trace",
                    "id": "t-1",
                    "expression": ".trace.input.prompt",
                }
            ),
            ctx,
        )

        body = result.split("\n", 1)[1]
        # Strings render bare (no surrounding quotes), matching backend
        # `jq` rendering.
        assert body == "hello"

    def test_iterate_emits_one_per_line(self):
        trace = _trace()
        spans = [_span("a"), _span("b", start_offset_s=1)]
        ctx = _ctx(trace, spans)
        tool = scan_module.ScanTool()

        result = tool.execute(
            json.dumps(
                {
                    "type": "trace",
                    "id": trace.id,
                    "expression": ".spans[].id",
                }
            ),
            ctx,
        )

        body = result.split("\n", 1)[1]
        assert body.splitlines() == ["a", "b"]

    def test_no_matches_renders_placeholder(self):
        ctx = _ctx(_trace(), [])
        tool = scan_module.ScanTool()

        result = tool.execute(
            json.dumps(
                {
                    "type": "trace",
                    "id": "t-1",
                    "expression": ".trace.nonexistent",
                }
            ),
            ctx,
        )

        body = result.split("\n", 1)[1]
        assert body == "<no matches>"


class TestErrorPropagation:
    def test_unknown_entity_returns_not_found_error(self):
        ctx = _ctx(_trace(), [])
        tool = scan_module.ScanTool()

        result = tool.execute(
            json.dumps({"type": "trace", "id": "missing", "expression": "."}),
            ctx,
        )

        assert "ERROR" in result
        assert "not found" in result.lower()

    def test_unsupported_grammar_returns_structured_error(self):
        ctx = _ctx(_trace(), [])
        tool = scan_module.ScanTool()

        result = tool.execute(
            json.dumps(
                {
                    "type": "trace",
                    "id": "t-1",
                    # Bindings (`as`) not supported.
                    "expression": ".trace as $x | $x",
                }
            ),
            ctx,
        )

        assert "ERROR" in result
        assert "unsupported expression" in result.lower()


class TestOutputCap:
    def test_oversized_output_truncates_with_refine_hint(self):
        # Use a large list iterated as strings to drive past the 16 KB cap.
        big_value = "x" * 200
        trace = _trace()
        # Stuff a large list into the trace's input.
        spans = [_span(f"s-{i}", input={"k": big_value}) for i in range(200)]
        ctx = _ctx(trace, spans)
        tool = scan_module.ScanTool()

        result = tool.execute(
            json.dumps(
                {
                    "type": "trace",
                    "id": trace.id,
                    "expression": ".spans[].input.k",
                }
            ),
            ctx,
        )

        assert "TRUNCATED" in result
        assert "refine" in result.lower()
        # Total result size must respect the cap (header excluded).
        body = result.split("\n", 1)[1]
        assert len(body) <= scan_module.OUTPUT_BYTE_CAP + len(
            scan_module.TRUNCATION_SUFFIX
        )


class TestEnvelopeFormat:
    def test_ok_envelope_header_format(self):
        ctx = _ctx(_trace(), [])
        tool = scan_module.ScanTool()

        result = tool.execute(
            json.dumps({"type": "trace", "id": "t-1", "expression": ".trace.name"}),
            ctx,
        )

        assert result.startswith("[scan: trace:t-1 | expression='.trace.name']")

    def test_error_envelope_header_format(self):
        ctx = _ctx(_trace(), [])
        tool = scan_module.ScanTool()

        result = tool.execute(
            json.dumps({"type": "trace", "id": "missing", "expression": "."}),
            ctx,
        )

        assert "ERROR" in result.splitlines()[0]

import datetime

from opik.message_processing.emulation import models

from opik.evaluation.suite_evaluators.agentic.compression import span_tree_serializer


def _now():
    return datetime.datetime(2026, 5, 13, 12, 0, 0)


def _trace(trace_id="t-1", **overrides):
    base = dict(
        id=trace_id,
        start_time=_now(),
        name="test-trace",
        project_name="default",
        source="sdk",
        input={"prompt": "hello"},
        output={"answer": "world"},
        end_time=_now() + datetime.timedelta(seconds=1),
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


class TestSerializeOverview:
    def test_flat_structure_with_parent_links(self):
        root = _span("root")
        child = _span("child", start_offset_s=1)

        result = span_tree_serializer.serialize_overview(
            _trace(),
            spans=[root, child],
            parent_by_child={"root": None, "child": "root"},
        )

        ids = [s["id"] for s in result["spans"]]
        assert ids == ["root", "child"]
        parent_links = {s["id"]: s["parent_span_id"] for s in result["spans"]}
        assert parent_links == {"root": None, "child": "root"}

    def test_trace_summary_counts_spans_and_errors(self):
        ok = _span("ok")
        err = _span(
            "err",
            start_offset_s=1,
            error_info={"exception_type": "X", "message": "m", "traceback": "tb"},
        )

        result = span_tree_serializer.serialize_overview(
            _trace(),
            spans=[ok, err],
            parent_by_child={"ok": None, "err": "ok"},
        )

        assert result["trace"]["span_count"] == 2
        assert result["trace"]["error_count"] == 1
        assert result["trace"]["has_error"] is False

    def test_parent_links_resolved_without_nested_spans_attribute(self):
        # `spans_for_trace` returns a flat list with `.spans` empty on every
        # node. The serializer must rely on the parent map, not walk `.spans`.
        root = _span("root")
        child = _span("child", start_offset_s=1)
        assert root.spans == []  # flat, as returned by spans_for_trace

        result = span_tree_serializer.serialize_overview(
            _trace(),
            spans=[root, child],
            parent_by_child={"root": None, "child": "root"},
        )

        parent_links = {s["id"]: s["parent_span_id"] for s in result["spans"]}
        assert parent_links == {"root": None, "child": "root"}

    def test_long_strings_truncated_with_suffix(self):
        long_input = "x" * 300
        result = span_tree_serializer.serialize_overview(
            _trace(input=long_input),
            spans=[],
            parent_by_child={},
        )
        assert "TRUNCATED" in result["trace"]["input"]
        assert result["trace"]["input"].startswith("x" * 200)

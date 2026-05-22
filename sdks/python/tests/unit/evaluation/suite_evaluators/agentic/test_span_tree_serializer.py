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
    def test_serialize_overview__flat_structure__preserves_parent_links(self):
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

    def test_serialize_overview__mixed_spans__trace_summary_counts_spans_and_errors(
        self,
    ):
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

    def test_serialize_overview__flat_spans__resolves_parent_links_from_map(self):
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

    def test_serialize_overview__long_trace_input__truncated_with_read_hint(self):
        # Long enough to trip the 500-char overview cap; the suffix must
        # carry an actionable `read(type='trace', id='<id>')` hint so the
        # judge knows exactly how to recover the un-truncated value.
        long_input = "x" * 800
        trace = _trace(input=long_input)

        result = span_tree_serializer.serialize_overview(
            trace,
            spans=[],
            parent_by_child={},
        )

        truncated = result["trace"]["input"]
        assert truncated != long_input
        assert truncated[: span_tree_serializer.OVERVIEW_IO_FLOOR_CHAR_LIMIT] == (
            "x" * span_tree_serializer.OVERVIEW_IO_FLOOR_CHAR_LIMIT
        )
        # Suffix is actionable: names the tool and the entity ref.
        assert f"read(type='trace', id='{trace.id}')" in truncated

    def test_serialize_overview__long_span_input__truncated_with_span_read_hint(self):
        # Same check for a span-level field: the hint must anchor at the
        # span entity, not the trace.
        long_input = "y" * 800
        span = _span("s-1")
        span.input = long_input

        result = span_tree_serializer.serialize_overview(
            _trace(),
            spans=[span],
            parent_by_child={span.id: None},
        )

        truncated = result["spans"][0]["input"]
        assert truncated != long_input
        assert f"read(type='span', id='{span.id}')" in truncated

    def test_serialize_overview__under_cap__no_truncation_or_hint(self):
        # Sanity: short values must round-trip unchanged with no suffix.
        result = span_tree_serializer.serialize_overview(
            _trace(input="short"),
            spans=[],
            parent_by_child={},
        )

        assert result["trace"]["input"] == "short"
        assert "TRUNCATED" not in result["trace"]["input"]

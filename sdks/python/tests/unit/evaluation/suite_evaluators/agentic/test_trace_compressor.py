"""Unit tests for the trace compressor's 3-tier adaptive logic."""

from opik.evaluation.suite_evaluators.agentic.compression import (
    tier as tier_module,
    trace_compressor,
)


def _trace(**overrides):
    base = {
        "id": "t-1",
        "name": "trace",
        "project_name": "default",
        "start_time": "2026-05-13T12:00:00",
        "end_time": "2026-05-13T12:00:01",
        "input": {"q": "hi"},
        "output": {"a": "there"},
        "metadata": None,
        "tags": None,
        "error_info": None,
    }
    base.update(overrides)
    return base


def _span(span_id, parent_span_id=None, **overrides):
    base = {
        "id": span_id,
        "name": span_id,
        "type": "general",
        "parent_span_id": parent_span_id,
        "start_time": "2026-05-13T12:00:00",
        "end_time": "2026-05-13T12:00:01",
        "input": None,
        "output": None,
        "metadata": None,
        "tags": None,
        "usage": None,
        "model": None,
        "provider": None,
        "error_info": None,
        "total_cost": None,
    }
    base.update(overrides)
    return base


class TestPickTier:
    def test_compress__small_payload__chooses_full_tier(self):
        trace = _trace()
        full = trace_compressor.build_full_json(trace, [])

        result = trace_compressor.compress(
            full_json=full, trace=trace, spans=[], parent_by_child={}
        )

        assert result.tier is tier_module.CompressionTier.FULL
        assert result.payload is full

    def test_compress__medium_payload__truncates_strings(self):
        # Force MEDIUM by inflating a string past FULL_TOKEN_LIMIT but
        # below MEDIUM_TOKEN_LIMIT. FULL_TOKEN_LIMIT*4 chars = 32k.
        big = "x" * 40_000
        trace = _trace(input={"prompt": big})
        full = trace_compressor.build_full_json(trace, [])

        result = trace_compressor.compress(
            full_json=full, trace=trace, spans=[], parent_by_child={}
        )

        assert result.tier is tier_module.CompressionTier.MEDIUM
        truncated = result.payload["trace"]["input"]["prompt"]
        assert truncated != big
        # MEDIUM-tier strings carry a scan-path hint pointing at the
        # cached composite shape (e.g. `.trace.input.prompt`).
        assert "scan('.trace.input.prompt')" in truncated

    def test_compress__large_payload__collapses_to_skeleton(self):
        # > MEDIUM_TOKEN_LIMIT*4 = 200k chars worth → SKELETON.
        huge_span = _span("s-big", input={"prompt": "y" * 300_000})
        trace = _trace()
        full = trace_compressor.build_full_json(trace, [huge_span])

        result = trace_compressor.compress(
            full_json=full,
            trace=trace,
            spans=[huge_span],
            parent_by_child={"s-big": None},
        )

        assert result.tier is tier_module.CompressionTier.SKELETON
        skeleton = result.payload
        # Skeleton drops content but preserves structural metadata.
        assert skeleton["name"] == "trace"
        assert skeleton["span_count"] == 1
        assert skeleton["span_tree"][0]["id"] == "s-big"
        assert "input" not in skeleton["span_tree"][0]


class TestForcedTier:
    def test_compress__forced_full__returns_payload_verbatim(self):
        trace = _trace()
        full = trace_compressor.build_full_json(trace, [])

        result = trace_compressor.compress(
            full_json=full,
            trace=trace,
            spans=[],
            parent_by_child={},
            forced_tier=tier_module.CompressionTier.FULL,
        )

        assert result.tier is tier_module.CompressionTier.FULL
        assert result.payload is full

    def test_compress__forced_skeleton__collapses_even_when_small(self):
        trace = _trace()
        spans = [_span("s-1"), _span("s-2", parent_span_id="s-1")]
        full = trace_compressor.build_full_json(trace, spans)

        result = trace_compressor.compress(
            full_json=full,
            trace=trace,
            spans=spans,
            parent_by_child={"s-1": None, "s-2": "s-1"},
            forced_tier=tier_module.CompressionTier.SKELETON,
        )

        assert result.tier is tier_module.CompressionTier.SKELETON
        # Skeleton tree nests s-2 under s-1.
        root = result.payload["span_tree"]
        assert len(root) == 1
        assert root[0]["id"] == "s-1"
        assert root[0]["spans"][0]["id"] == "s-2"

    def test_compress__forced_summary__reports_as_skeleton(self):
        # This compressor has no SUMMARY rendering; SUMMARY requests are
        # served as SKELETON and reported as such so the caller sees the
        # actual tier they received.
        trace = _trace()
        full = trace_compressor.build_full_json(trace, [])

        result = trace_compressor.compress(
            full_json=full,
            trace=trace,
            spans=[],
            parent_by_child={},
            forced_tier=tier_module.CompressionTier.SUMMARY,
        )

        assert result.tier is tier_module.CompressionTier.SKELETON


class TestSkeletonBuilder:
    def test_skeleton__spans_with_errors__error_count_matches(self):
        trace = _trace()
        spans = [
            _span("a"),
            _span("b", error_info={"message": "boom"}),
            _span("c", error_info={"message": "kaboom"}),
        ]
        full = trace_compressor.build_full_json(trace, spans)

        result = trace_compressor.compress(
            full_json=full,
            trace=trace,
            spans=spans,
            parent_by_child={s["id"]: None for s in spans},
            forced_tier=tier_module.CompressionTier.SKELETON,
        )

        assert result.payload["error_count"] == 2

    def test_skeleton__orphan_spans__promoted_to_roots(self):
        trace = _trace()
        # `child` points at a parent not in the spans list.
        spans = [_span("child", parent_span_id="ghost")]
        full = trace_compressor.build_full_json(trace, spans)

        result = trace_compressor.compress(
            full_json=full,
            trace=trace,
            spans=spans,
            parent_by_child={"child": "ghost"},
            forced_tier=tier_module.CompressionTier.SKELETON,
        )

        roots = result.payload["span_tree"]
        assert [r["id"] for r in roots] == ["child"]

    def test_skeleton__iso_timestamps__duration_ms_computed(self):
        trace = _trace(start_time="2026-05-13T12:00:00", end_time="2026-05-13T12:00:02")
        full = trace_compressor.build_full_json(trace, [])

        result = trace_compressor.compress(
            full_json=full,
            trace=trace,
            spans=[],
            parent_by_child={},
            forced_tier=tier_module.CompressionTier.SKELETON,
        )

        assert result.payload["total_duration_ms"] == 2000.0

    def test_skeleton__missing_end_time__duration_ms_none(self):
        trace = _trace(end_time=None)
        full = trace_compressor.build_full_json(trace, [])

        result = trace_compressor.compress(
            full_json=full,
            trace=trace,
            spans=[],
            parent_by_child={},
            forced_tier=tier_module.CompressionTier.SKELETON,
        )

        assert result.payload["total_duration_ms"] is None

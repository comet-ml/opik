"""Unit tests for the generic 2-tier compressor (SPAN entities)."""

from opik.evaluation.suite_evaluators.agentic.compression import (
    generic_compressor,
    tier as tier_module,
)


def _span_dict(**overrides):
    base = {
        "id": "s-1",
        "name": "span",
        "type": "general",
        "input": None,
        "output": None,
    }
    base.update(overrides)
    return base


class TestPickTier:
    def test_compress__small_payload__chooses_full_tier(self):
        span = _span_dict()

        result = generic_compressor.compress(span)

        assert result.tier is tier_module.CompressionTier.FULL
        assert result.payload is span

    def test_compress__large_payload__chooses_medium_and_truncates(self):
        big = "x" * 40_000
        span = _span_dict(output={"text": big})

        result = generic_compressor.compress(span)

        assert result.tier is tier_module.CompressionTier.MEDIUM
        truncated = result.payload["output"]["text"]
        assert truncated != big
        assert "scan('.output.text')" in truncated


class TestForcedTier:
    def test_compress__forced_full__keeps_payload_verbatim(self):
        big = "x" * 40_000
        span = _span_dict(output={"text": big})

        result = generic_compressor.compress(
            span, forced_tier=tier_module.CompressionTier.FULL
        )

        assert result.tier is tier_module.CompressionTier.FULL
        # Full tier means no truncation even if the size exceeds budget.
        assert result.payload["output"]["text"] == big

    def test_compress__forced_skeleton__collapses_to_medium(self):
        # GenericCompressor has no SKELETON renderer; SKELETON / SUMMARY
        # requests collapse to MEDIUM (matches GenericCompressor.java).
        span = _span_dict()

        result = generic_compressor.compress(
            span, forced_tier=tier_module.CompressionTier.SKELETON
        )

        assert result.tier is tier_module.CompressionTier.MEDIUM

    def test_compress__forced_summary__collapses_to_medium(self):
        span = _span_dict()

        result = generic_compressor.compress(
            span, forced_tier=tier_module.CompressionTier.SUMMARY
        )

        assert result.tier is tier_module.CompressionTier.MEDIUM

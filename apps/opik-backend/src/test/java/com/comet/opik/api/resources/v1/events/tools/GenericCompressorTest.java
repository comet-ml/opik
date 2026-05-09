package com.comet.opik.api.resources.v1.events.tools;

import com.comet.opik.utils.JsonUtils;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GenericCompressorTest {

    private final GenericCompressor compressor = new GenericCompressor();

    @Test
    void smallEntityReturnsFullTier() {
        var json = JsonUtils.getJsonNodeFromString("{\"name\":\"tiny\"}");

        var result = compressor.compress(json, null);

        assertThat(result.tier()).isEqualTo(CompressionTier.FULL);
        assertThat(result.payload()).isSameAs(json);
    }

    @Test
    void largeEntityFallsToMediumWithStringTruncation() {
        var bigInput = "x".repeat(40_000); // ~10K tokens at 4 chars/token, exceeds default 8K limit
        var json = JsonUtils.getJsonNodeFromString("{\"big\":\"%s\"}".formatted(bigInput));

        var result = compressor.compress(json, null);

        assertThat(result.tier()).isEqualTo(CompressionTier.MEDIUM);
        var truncated = result.payload().get("big").asText();
        assertThat(truncated).hasSizeLessThan(bigInput.length());
        assertThat(truncated).contains("[TRUNCATED");
        assertThat(truncated).contains("use jq('.big') to see full");
    }

    @Test
    void forcedFullTierSkipsTruncationEvenOnLargeInput() {
        var bigInput = "x".repeat(40_000);
        var json = JsonUtils.getJsonNodeFromString("{\"big\":\"%s\"}".formatted(bigInput));

        var result = compressor.compress(json, CompressionTier.FULL);

        assertThat(result.tier()).isEqualTo(CompressionTier.FULL);
        assertThat(result.payload().get("big").asText()).hasSize(bigInput.length());
    }

    @Test
    void forcedMediumTierTruncatesEvenOnSmallInput() {
        // Just over the 1000-char per-string truncation threshold so we get a
        // deterministic dropped-chars count in the suffix.
        var json = JsonUtils.getJsonNodeFromString(
                "{\"text\":\"%s\"}".formatted("a".repeat(1_300)));

        var result = compressor.compress(json, CompressionTier.MEDIUM);

        assertThat(result.tier()).isEqualTo(CompressionTier.MEDIUM);
        assertThat(result.payload().get("text").asText()).contains("[TRUNCATED 300 chars");
    }

    @Test
    void skeletonAndSummaryRequestsCollapseToMedium() {
        var json = JsonUtils.getJsonNodeFromString("{\"text\":\"hello\"}");

        var skeleton = compressor.compress(json, CompressionTier.SKELETON);
        var summary = compressor.compress(json, CompressionTier.SUMMARY);

        assertThat(skeleton.tier()).isEqualTo(CompressionTier.MEDIUM);
        assertThat(summary.tier()).isEqualTo(CompressionTier.MEDIUM);
    }

}
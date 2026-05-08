package com.comet.opik.api.resources.v1.events.tools;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Singleton;
import lombok.NonNull;

/**
 * Type-agnostic compressor for entities without a bespoke compressor (span,
 * dataset_item, project, thread). Operates on the already-serialized JSON.
 *
 * <ul>
 *   <li>FULL — rendered size &lt; {@link #FULL_TOKEN_LIMIT}: the entity's JSON untouched.</li>
 *   <li>MEDIUM — rendered size ≥ {@link #FULL_TOKEN_LIMIT}: same tree, but every string node
 *       longer than {@link #STRING_TRUNCATION_LENGTH} chars is replaced with a
 *       jq-path-aware truncation marker. Structure is preserved verbatim.</li>
 * </ul>
 *
 * <p>The {@code tier} arg passed to {@code read} is honored within the FULL/MEDIUM
 * ladder; SKELETON / SUMMARY requests collapse to MEDIUM and the returned tier
 * reflects what was actually produced.
 *
 * <p>Returned {@link CompressionResult#payload()} is the bare (possibly
 * truncated) entity JSON — {@code ReadTool} wraps it in the {@code data}
 * field of the response envelope.
 */
@Singleton
public final class GenericCompressor {

    static final int FULL_TOKEN_LIMIT = 8_000;
    static final int STRING_TRUNCATION_LENGTH = 1_000;

    public CompressionResult compress(@NonNull JsonNode entityJson, CompressionTier forcedTier) {
        return compress(entityJson, forcedTier, PathAwareTruncator.SuffixStyle.WITH_JQ_HINT);
    }

    /**
     * Variant that lets the caller pick the truncation suffix style. Pass
     * {@link PathAwareTruncator.SuffixStyle#BARE} when the cache itself was
     * capped, since the {@code use jq(...) to see full} pointer would be a
     * lie — the cache has no full value to recover.
     */
    CompressionResult compress(@NonNull JsonNode entityJson, CompressionTier forcedTier,
            @NonNull PathAwareTruncator.SuffixStyle suffix) {

        CompressionTier tier = pickTier(entityJson, forcedTier);
        JsonNode data = tier == CompressionTier.FULL
                ? entityJson
                : PathAwareTruncator.truncate(entityJson, STRING_TRUNCATION_LENGTH, suffix);
        return CompressionResult.builder()
                .payload(data)
                .tier(tier)
                .build();
    }

    private static CompressionTier pickTier(JsonNode entityJson, CompressionTier forced) {
        if (forced == null) {
            int estimate = Tokens.estimate(entityJson.toString());
            return estimate < FULL_TOKEN_LIMIT ? CompressionTier.FULL : CompressionTier.MEDIUM;
        }
        return switch (forced) {
            case FULL -> CompressionTier.FULL;
            case MEDIUM, SKELETON, SUMMARY -> CompressionTier.MEDIUM;
        };
    }
}
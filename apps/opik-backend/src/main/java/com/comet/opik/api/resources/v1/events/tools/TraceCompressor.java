package com.comet.opik.api.resources.v1.events.tools;

import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Singleton;
import lombok.NonNull;

import java.util.List;

/**
 * Bespoke adaptive compressor for traces. The cached FULL form is the
 * composite {@code {trace, spans}}; this compressor produces three tiers:
 *
 * <ul>
 *   <li>FULL — &lt; {@link #FULL_TOKEN_LIMIT}: full composite untouched.</li>
 *   <li>MEDIUM — between {@link #FULL_TOKEN_LIMIT} and {@link #MEDIUM_TOKEN_LIMIT}:
 *       composite with all string values longer than {@link #STRING_TRUNCATION_LENGTH}
 *       replaced via the path-aware truncator. jq paths target the cached
 *       composite (e.g. {@code .trace.input}, {@code .spans[2].output}).</li>
 *   <li>SKELETON — ≥ {@link #MEDIUM_TOKEN_LIMIT}: identity fields + counts +
 *       a minimal span tree (id / name / type / parent / children only).</li>
 * </ul>
 *
 * <p>SUMMARY isn't in this compressor's ladder and collapses to SKELETON.
 *
 * <p>Returned {@link CompressionResult#payload()} is the bare inner content;
 * {@code ReadTool} wraps it in the {@code data} field of the response envelope.
 */
@Singleton
public final class TraceCompressor implements EntityCompressor {

    static final int FULL_TOKEN_LIMIT = 8_000;
    static final int MEDIUM_TOKEN_LIMIT = 50_000;
    static final int STRING_TRUNCATION_LENGTH = 1000;

    @Override
    public EntityType type() {
        return EntityType.TRACE;
    }

    /**
     * Builds the FULL composite JSON {@code {"trace": ..., "spans": [...]}}. Exposed
     * so {@code ReadTool} can cache it before invoking compression.
     */
    public JsonNode buildFullJson(@NonNull Trace trace, @NonNull List<Span> spans) {
        var mapper = JsonUtils.getMapper();
        ObjectNode node = mapper.createObjectNode();
        node.set("trace", mapper.valueToTree(trace));
        node.set("spans", mapper.valueToTree(spans));
        return node;
    }

    public CompressionResult compress(@NonNull JsonNode fullJson,
            @NonNull Trace trace,
            @NonNull List<Span> spans,
            CompressionTier forcedTier) {
        return compress(fullJson, trace, spans, forcedTier, PathAwareTruncator.SuffixStyle.WITH_JQ_HINT);
    }

    /**
     * Variant that lets the caller pick the truncation suffix style. Pass
     * {@link PathAwareTruncator.SuffixStyle#BARE} when the cache itself was
     * capped, since the {@code use jq(...) to see full} pointer would be a
     * lie — the cache has no full value to recover.
     */
    CompressionResult compress(@NonNull JsonNode fullJson,
            @NonNull Trace trace,
            @NonNull List<Span> spans,
            CompressionTier forcedTier,
            @NonNull PathAwareTruncator.SuffixStyle suffix) {

        CompressionTier tier = pickTier(fullJson, forcedTier);
        JsonNode payload = switch (tier) {
            case FULL -> fullJson;
            case MEDIUM -> PathAwareTruncator.truncate(fullJson, STRING_TRUNCATION_LENGTH, suffix);
            case SKELETON, SUMMARY -> buildSkeleton(trace, spans);
        };
        CompressionTier reportedTier = tier == CompressionTier.SUMMARY ? CompressionTier.SKELETON : tier;
        return CompressionResult.builder()
                .payload(payload)
                .tier(reportedTier)
                .build();
    }

    private static CompressionTier pickTier(JsonNode fullJson, CompressionTier forced) {
        if (forced != null) {
            return forced;
        }
        int estimate = Tokens.estimate(fullJson.toString());
        if (estimate < FULL_TOKEN_LIMIT) {
            return CompressionTier.FULL;
        }
        if (estimate < MEDIUM_TOKEN_LIMIT) {
            return CompressionTier.MEDIUM;
        }
        return CompressionTier.SKELETON;
    }

    private static JsonNode buildSkeleton(Trace trace, List<Span> spans) {
        var mapper = JsonUtils.getMapper();
        ObjectNode node = mapper.createObjectNode();
        node.put("name", trace.name());
        if (trace.projectId() != null) {
            node.put("project_id", trace.projectId().toString());
        }
        node.put("span_count", spans.size());
        node.put("error_count", (int) spans.stream().filter(s -> s.errorInfo() != null).count());
        if (trace.duration() != null) {
            node.put("total_duration_ms", trace.duration());
        }
        node.set("span_tree", SpanHierarchy.toTree(spans, TraceCompressor::buildSkeletonNode));
        return node;
    }

    private static ObjectNode buildSkeletonNode(Span span) {
        ObjectNode node = JsonUtils.getMapper().createObjectNode();
        node.put("id", span.id().toString());
        node.put("name", span.name());
        node.put("type", span.type() != null ? span.type().toString() : null);
        return node;
    }
}

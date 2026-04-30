package com.comet.opik.api.resources.v1.events.tools;

import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Singleton;
import lombok.NonNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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

        CompressionTier tier = pickTier(fullJson, forcedTier);
        JsonNode payload = switch (tier) {
            case FULL -> fullJson;
            case MEDIUM -> PathAwareTruncator.truncate(fullJson, STRING_TRUNCATION_LENGTH);
            case SKELETON, SUMMARY -> buildSkeleton(trace, spans);
        };
        CompressionTier reportedTier = tier == CompressionTier.SUMMARY ? CompressionTier.SKELETON : tier;
        return new CompressionResult(payload, reportedTier);
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

        ArrayNode tree = mapper.createArrayNode();
        Map<UUID, List<Span>> childrenByParent = new LinkedHashMap<>();
        Set<UUID> ids = new HashSet<>();
        List<Span> roots = new ArrayList<>();

        for (var s : spans) {
            ids.add(s.id());
        }
        for (var s : spans) {
            if (s.parentSpanId() == null || !ids.contains(s.parentSpanId())) {
                roots.add(s);
            } else {
                childrenByParent.computeIfAbsent(s.parentSpanId(), k -> new ArrayList<>()).add(s);
            }
        }
        for (var root : roots) {
            tree.add(buildSkeletonNode(root, childrenByParent));
        }
        node.set("span_tree", tree);
        return node;
    }

    private static ObjectNode buildSkeletonNode(Span span, Map<UUID, List<Span>> childrenByParent) {
        var mapper = JsonUtils.getMapper();
        ObjectNode node = mapper.createObjectNode();
        node.put("id", span.id().toString());
        node.put("name", span.name());
        node.put("type", span.type() != null ? span.type().toString() : null);
        var children = childrenByParent.get(span.id());
        if (children != null && !children.isEmpty()) {
            ArrayNode arr = mapper.createArrayNode();
            for (var child : children) {
                arr.add(buildSkeletonNode(child, childrenByParent));
            }
            node.set("children", arr);
        }
        return node;
    }
}

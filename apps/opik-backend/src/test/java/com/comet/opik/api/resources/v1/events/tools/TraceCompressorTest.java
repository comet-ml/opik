package com.comet.opik.api.resources.v1.events.tools;

import com.comet.opik.api.ErrorInfo;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.domain.SpanType;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TraceCompressorTest {

    private final TraceCompressor compressor = new TraceCompressor();

    @Test
    void typeIsTrace() {
        assertThat(compressor.type()).isEqualTo(EntityType.TRACE);
    }

    @Test
    void buildFullJsonHasTraceAndSpansFields() {
        var trace = trace("hello");
        var spans = List.of(span("only", null));

        var full = compressor.buildFullJson(trace, spans);

        assertThat(full.has("trace")).isTrue();
        assertThat(full.has("spans")).isTrue();
        assertThat(full.get("spans").isArray()).isTrue();
        assertThat(full.get("spans").size()).isEqualTo(1);
    }

    @Test
    void smallTraceReturnsFullTier() {
        var trace = trace("ok");
        var spans = List.of(span("only", null));
        var full = compressor.buildFullJson(trace, spans);

        var result = compressor.compress(full, trace, spans, null);

        assertThat(result.tier()).isEqualTo(CompressionTier.FULL);
        assertThat(result.payload()).isSameAs(full);
    }

    @Test
    void mediumTierTruncatesStringsWithJqPath() {
        var bigInput = "x".repeat(40_000);
        var trace = traceWithLargeInput(bigInput);
        var spans = List.<Span>of();
        var full = compressor.buildFullJson(trace, spans);

        var result = compressor.compress(full, trace, spans, null);

        assertThat(result.tier()).isEqualTo(CompressionTier.MEDIUM);
        // jq path is rooted at the cached composite — input field on the trace
        var traceNode = result.payload().get("trace");
        var inputText = traceNode.get("input").get("user_query").asText();
        assertThat(inputText).contains("use jq('.trace.input.user_query') to see full");
    }

    @Test
    void skeletonTierForcedReturnsCountsAndTree() {
        var parent = span("parent", null);
        var child = span("child", parent.id());
        var spans = List.of(parent, child);
        var trace = trace("big");
        var full = compressor.buildFullJson(trace, spans);

        var result = compressor.compress(full, trace, spans, CompressionTier.SKELETON);

        assertThat(result.tier()).isEqualTo(CompressionTier.SKELETON);
        ObjectNode skeleton = (ObjectNode) result.payload();
        assertThat(skeleton.get("span_count").asInt()).isEqualTo(2);
        assertThat(skeleton.get("error_count").asInt()).isEqualTo(0);
        assertThat(skeleton.has("span_tree")).isTrue();
        assertThat(skeleton.get("span_tree").get(0).get("name").asText()).isEqualTo("parent");
        assertThat(skeleton.get("span_tree").get(0).get("children").get(0).get("name").asText())
                .isEqualTo("child");
    }

    @Test
    void summaryRequestCollapsesToSkeleton() {
        var spans = List.<Span>of();
        var trace = trace("any");
        var full = compressor.buildFullJson(trace, spans);

        var result = compressor.compress(full, trace, spans, CompressionTier.SUMMARY);

        assertThat(result.tier()).isEqualTo(CompressionTier.SKELETON);
    }

    @Test
    void errorCountsErroredSpans() {
        var ok = span("ok", null);
        var failed = Span.builder()
                .id(UUID.randomUUID())
                .traceId(UUID.randomUUID())
                .name("failed")
                .type(SpanType.general)
                .startTime(Instant.now())
                .errorInfo(ErrorInfo.builder().message("boom").exceptionType("RuntimeError").build())
                .build();
        var spans = List.of(ok, failed);
        var trace = trace("traceA");
        var full = compressor.buildFullJson(trace, spans);

        var result = compressor.compress(full, trace, spans, CompressionTier.SKELETON);

        assertThat(result.payload().get("error_count").asInt()).isEqualTo(1);
    }

    private static Trace trace(String name) {
        return Trace.builder()
                .id(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .name(name)
                .startTime(Instant.now())
                .build();
    }

    private static Trace traceWithLargeInput(String big) {
        JsonNode input = JsonUtils.getJsonNodeFromString("{\"user_query\":\"%s\"}".formatted(big));
        return Trace.builder()
                .id(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .name("big")
                .startTime(Instant.now())
                .input(input)
                .build();
    }

    private static Span span(String name, UUID parent) {
        return Span.builder()
                .id(UUID.randomUUID())
                .traceId(UUID.randomUUID())
                .parentSpanId(parent)
                .name(name)
                .type(SpanType.general)
                .startTime(Instant.now())
                .build();
    }
}
package com.comet.opik.api.resources.v1.events.tools;

import com.comet.opik.api.Trace;
import com.comet.opik.domain.DatasetItemService;
import com.comet.opik.domain.DatasetService;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.domain.SpanService;
import com.comet.opik.domain.TraceService;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ReadToolTest {

    private final TraceService traceService = mock(TraceService.class);
    private final SpanService spanService = mock(SpanService.class);
    private final DatasetService datasetService = mock(DatasetService.class);
    private final DatasetItemService datasetItemService = mock(DatasetItemService.class);
    private final ProjectService projectService = mock(ProjectService.class);
    private final TraceCompressor traceCompressor = new TraceCompressor();
    private final DatasetCompressor datasetCompressor = new DatasetCompressor();
    private final GenericCompressor genericCompressor = new GenericCompressor();

    private final ReadTool tool = new ReadTool(traceService, spanService, datasetService,
            datasetItemService, projectService, traceCompressor, datasetCompressor, genericCompressor);

    @Test
    void specHasExpectedNameAndRequiredFields() {
        var spec = tool.spec();

        assertThat(spec.name()).isEqualTo(ReadTool.NAME);
        assertThat(spec.parameters().required()).contains("type", "id");
    }

    @Test
    void missingArgumentsReturnsError() {
        var ctx = newContextWithSeededTrace();

        var result = JsonUtils.getJsonNodeFromString(
                tool.execute("{\"id\": \"abc\"}", ctx));

        assertThat(result.get("error").asText()).contains("Missing required argument: type");
    }

    @Test
    void unknownTypeReturnsError() {
        var ctx = newContextWithSeededTrace();

        var result = JsonUtils.getJsonNodeFromString(
                tool.execute("{\"type\": \"unicorn\", \"id\": \"abc\"}", ctx));

        assertThat(result.get("error").asText()).contains("Unknown type");
    }

    @Test
    void unknownTierReturnsError() {
        var ctx = newContextWithSeededTrace();
        var trace = ctx.getTrace();

        var result = JsonUtils.getJsonNodeFromString(
                tool.execute("{\"type\": \"trace\", \"id\": \"%s\", \"tier\": \"WHATEVER\"}"
                        .formatted(trace.id()), ctx));

        assertThat(result.get("error").asText()).contains("Unknown tier");
    }

    @Test
    void invalidUuidReturnsError() {
        var ctx = newContextWithSeededTrace();

        var result = JsonUtils.getJsonNodeFromString(
                tool.execute("{\"type\": \"trace\", \"id\": \"not-a-uuid\"}", ctx));

        assertThat(result.get("error").asText()).contains("Invalid id format");
    }

    @Test
    void threadTypeIsRejected() {
        var ctx = newContextWithSeededTrace();

        var result = JsonUtils.getJsonNodeFromString(
                tool.execute("{\"type\": \"thread\", \"id\": \"any-string\"}", ctx));

        assertThat(result.get("error").asText()).contains("type=thread is not supported");
    }

    @Test
    void preSeededTraceReturnsFromCacheWithoutServiceCall() {
        var ctx = newContextWithSeededTrace();
        var trace = ctx.getTrace();

        var json = tool.execute(
                "{\"type\": \"trace\", \"id\": \"%s\"}".formatted(trace.id()), ctx);
        var result = JsonUtils.getJsonNodeFromString(json);

        assertThat(result.get("type").asText()).isEqualTo("trace");
        assertThat(result.get("id").asText()).isEqualTo(trace.id().toString());
        assertThat(result.get("tier").asText()).isEqualTo(CompressionTier.FULL.name());
        assertThat(result.has("data")).isTrue();
        assertThat(result.get("data").has("trace")).isTrue();
        assertThat(result.get("data").has("spans")).isTrue();

        verifyNoInteractions(traceService, spanService);
    }

    @Test
    void preSeededTraceWithForcedSkeletonReturnsSkeleton() {
        var ctx = newContextWithSeededTrace();
        var trace = ctx.getTrace();

        var result = JsonUtils.getJsonNodeFromString(
                tool.execute("{\"type\": \"trace\", \"id\": \"%s\", \"tier\": \"SKELETON\"}"
                        .formatted(trace.id()), ctx));

        assertThat(result.get("tier").asText()).isEqualTo(CompressionTier.SKELETON.name());
        assertThat(result.get("data").has("span_count")).isTrue();
        assertThat(result.get("data").has("span_tree")).isTrue();
    }

    @Test
    void cacheHitForGenericEntityReturnsCachedView() {
        var ctx = newContextWithSeededTrace();
        var spanId = UUID.randomUUID();
        var spanJson = JsonUtils.getJsonNodeFromString("{\"id\":\"%s\",\"name\":\"cached\"}".formatted(spanId));
        ctx.cache(new EntityRef(EntityType.SPAN, spanId.toString()), spanJson);

        var result = JsonUtils.getJsonNodeFromString(
                tool.execute("{\"type\": \"span\", \"id\": \"%s\"}".formatted(spanId), ctx));

        assertThat(result.get("type").asText()).isEqualTo("span");
        assertThat(result.get("tier").asText()).isEqualTo(CompressionTier.FULL.name());
        assertThat(result.get("data").get("name").asText()).isEqualTo("cached");

        verifyNoInteractions(spanService);
    }

    @Test
    void capFallbackPrefersLargestThresholdWhenItFits() {
        // Single huge string blows the cap. The 100K (largest) threshold easily
        // brings the cache form well under 10 MB, so we expect the cached
        // string to be at the 100K limit, not 10K or 1K.
        var spanId = UUID.randomUUID();
        var ref = new EntityRef(EntityType.SPAN, spanId.toString());
        var ctx = newContextWithSeededTrace();

        String huge = "x".repeat(ReadTool.CACHE_CAP_CHARS + 100); // > 10 MB
        var spanJson = JsonUtils.getMapper().createObjectNode();
        spanJson.put("id", spanId.toString());
        spanJson.put("input", huge);
        ctx.cache(ref, spanJson);

        // Trigger the cap path.
        tool.execute("{\"type\": \"span\", \"id\": \"%s\"}".formatted(spanId), ctx);

        assertThat(ctx.isTruncated(ref)).isTrue();
        var cached = ctx.getCached(ref).orElseThrow();
        var cachedInput = cached.get("input").asText();
        // 100K kept + bare suffix, no jq hint.
        assertThat(cachedInput).hasSizeBetween(100_000, 100_100);
        assertThat(cachedInput).contains("[TRUNCATED").doesNotContain("use jq");
    }

    @Test
    void capFallbackTightensWhenLargestThresholdStillOverflows() {
        // Build many strings that fit through the 100K threshold without
        // truncation (each is 60K, < 100K) but together remain over the 10 MB
        // cap → ladder must drop to 10K. 250 fields × 60K chars each = ~15 MB
        // raw; at 100K each, no truncation fires and the cache is still ~15 MB
        // (over); at 10K each, ~2.5 MB (under).
        var spanId = UUID.randomUUID();
        var ref = new EntityRef(EntityType.SPAN, spanId.toString());
        var ctx = newContextWithSeededTrace();

        var spanJson = JsonUtils.getMapper().createObjectNode();
        spanJson.put("id", spanId.toString());
        String filler = "y".repeat(60_000);
        for (int i = 0; i < 250; i++) {
            spanJson.put("field_" + i, filler);
        }
        ctx.cache(ref, spanJson);

        tool.execute("{\"type\": \"span\", \"id\": \"%s\"}".formatted(spanId), ctx);

        var cached = ctx.getCached(ref).orElseThrow();
        var sampleField = cached.get("field_0").asText();
        // 100K threshold would have kept ~100K — but the resulting cache was still
        // over 10 MB, so the ladder must have dropped to 10K.
        assertThat(sampleField).hasSizeBetween(10_000, 10_100);
        assertThat(cached.toString().length())
                .as("after multi-tier fallback the cached form must fit under the cap")
                .isLessThanOrEqualTo(ReadTool.CACHE_CAP_CHARS);
    }

    @Test
    void responseIncludesCacheWarningWhenFullExceedsCap() {
        var ctx = newContextWithSeededTrace();
        var spanId = UUID.randomUUID();

        // Build a JSON whose stringified size exceeds the 10MB char cap.
        String huge = "x".repeat(ReadTool.CACHE_CAP_CHARS + 100);
        var spanJson = JsonUtils.getJsonNodeFromString(
                "{\"id\":\"%s\",\"input\":\"%s\"}".formatted(spanId, huge));
        ctx.cache(new EntityRef(EntityType.SPAN, spanId.toString()), spanJson);

        var result = JsonUtils.getJsonNodeFromString(
                tool.execute("{\"type\": \"span\", \"id\": \"%s\"}".formatted(spanId), ctx));

        // First read after replacing oversized cache: still includes the warning since the
        // cap is checked against the cached node which is the over-cap form.
        assertThat(result.has("cache_warning")).isTrue();
        assertThat(result.get("cache_warning").asText()).contains("MEDIUM-tier");
    }

    @Test
    void cacheWarningPersistsOnSubsequentReadAfterTruncation() {
        var ctx = newContextWithSeededTrace();
        var spanId = UUID.randomUUID();
        var ref = new EntityRef(EntityType.SPAN, spanId.toString());

        // Empty cache. First read of an oversized fetched entity caches the
        // truncated form and emits the warning.
        String huge = "x".repeat(ReadTool.CACHE_CAP_CHARS + 100);
        var oversized = JsonUtils.getJsonNodeFromString(
                "{\"id\":\"%s\",\"input\":\"%s\"}".formatted(spanId, huge));
        // Pre-populate the cache as if the (uncapped) fetch already completed,
        // so we don't need to wire SpanService just to drive the cap path.
        ctx.cache(ref, oversized);

        var first = JsonUtils.getJsonNodeFromString(
                tool.execute("{\"type\": \"span\", \"id\": \"%s\"}".formatted(spanId), ctx));
        assertThat(first.has("cache_warning")).isTrue();
        assertThat(ctx.isTruncated(ref))
                .as("cap must be sticky after first triggering call")
                .isTrue();

        // Simulate the cache now holding the truncated (under-cap) form, as it
        // would after a real fetch path: ReadTool would have replaced the cache
        // with outcome.cachedNode. We mirror that here.
        ctx.cache(ref, JsonUtils.getJsonNodeFromString(
                "{\"id\":\"%s\",\"input\":\"x[TRUNCATED ...]\"}".formatted(spanId)));

        var second = JsonUtils.getJsonNodeFromString(
                tool.execute("{\"type\": \"span\", \"id\": \"%s\"}".formatted(spanId), ctx));
        assertThat(second.has("cache_warning"))
                .as("warning must persist on subsequent reads of a truncated cache")
                .isTrue();
        assertThat(second.get("cache_warning").asText()).contains("MEDIUM-tier");
        // The compressor sees a small (truncated) cache and would autopick FULL;
        // ReadTool must downgrade the reported tier to MEDIUM so the LLM doesn't
        // see "tier=FULL" alongside a cache_warning.
        assertThat(second.get("tier").asText())
                .as("tier=FULL would lie about fidelity when cache is truncated")
                .isEqualTo(CompressionTier.MEDIUM.name());
    }

    @Test
    void jqHintsAreStrippedFromTruncationSuffixesWhenCacheIsCapped() {
        var ctx = newContextWithSeededTrace();
        var spanId = UUID.randomUUID();
        var ref = new EntityRef(EntityType.SPAN, spanId.toString());

        // Pre-seed the cache with a value containing a long string AND already
        // mark the entity as truncated. The compressor will produce truncation
        // suffixes embedding `— use jq('.input') to see full`; ReadTool should
        // strip that segment because the cache can't actually deliver the full
        // value (cache_warning is the authoritative note).
        String longInput = "y".repeat(2_000); // > GenericCompressor 1000-char threshold for MEDIUM
        var json = JsonUtils.getMapper().createObjectNode();
        json.put("id", spanId.toString());
        json.put("input", longInput);
        ctx.cache(ref, json);
        ctx.markTruncated(ref);

        var result = JsonUtils.getJsonNodeFromString(
                tool.execute("{\"type\": \"span\", \"id\": \"%s\", \"tier\": \"MEDIUM\"}"
                        .formatted(spanId), ctx));

        var inputText = result.get("data").get("input").asText();
        assertThat(inputText)
                .as("truncation suffix must keep the bare [TRUNCATED N chars] form")
                .containsPattern("\\[TRUNCATED [0-9,]+ chars]");
        assertThat(inputText)
                .as("dishonest 'use jq to see full' segment must be stripped")
                .doesNotContain("use jq")
                .doesNotContain("to see full");
        assertThat(result.has("cache_warning")).isTrue();
    }

    @Test
    void jqHintsAreKeptWhenCacheIsNotCapped() {
        // Counterpart to the above: when the cache is healthy, the per-string
        // hints DO point at recoverable values and must be preserved so the
        // agent knows where to drill in.
        var trace = Trace.builder()
                .id(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .name("active")
                .startTime(Instant.now())
                .build();
        var ctx = new TraceToolContext(trace, List.of(), "ws", "user");

        var spanId = UUID.randomUUID();
        var ref = new EntityRef(EntityType.SPAN, spanId.toString());
        String longInput = "z".repeat(2_000);
        var json = JsonUtils.getMapper().createObjectNode();
        json.put("id", spanId.toString());
        json.put("input", longInput);
        ctx.cache(ref, json);
        // Note: NO markTruncated — cache is healthy.

        var result = JsonUtils.getJsonNodeFromString(
                tool.execute("{\"type\": \"span\", \"id\": \"%s\", \"tier\": \"MEDIUM\"}"
                        .formatted(spanId), ctx));

        var inputText = result.get("data").get("input").asText();
        assertThat(inputText).contains("use jq('.input') to see full");
        assertThat(result.has("cache_warning")).isFalse();
    }

    private static TraceToolContext newContextWithSeededTrace() {
        var trace = Trace.builder()
                .id(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .name("active")
                .startTime(Instant.now())
                .build();
        var ctx = new TraceToolContext(trace, List.of(), "ws", "user");
        // Mirror the production seed: cache the {trace, spans} composite under EntityRef(TRACE, id).
        JsonNode composite = new TraceCompressor().buildFullJson(trace, List.of());
        ctx.cache(new EntityRef(EntityType.TRACE, trace.id().toString()), composite);
        return ctx;
    }
}
